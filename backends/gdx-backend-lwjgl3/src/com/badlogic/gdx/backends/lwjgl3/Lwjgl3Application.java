/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.lwjgl3;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.audio.mock.MockAudio;
import com.badlogic.gdx.utils.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.*;
import org.lwjgl.system.Callback;

import java.io.File;
import java.io.PrintStream;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;

public class Lwjgl3Application extends Lwjgl3Runnables implements Application {

	private final Lwjgl3ApplicationConfiguration config;

	private final Lwjgl3Graphics graphics;
	private final Audio audio;
	private final Files files;
	private final Net net;
	private final ObjectMap<String, Preferences> preferences = new ObjectMap<>();

	private final Thread renderThread;
	private volatile boolean updating = true;
	private volatile boolean rendering = true;
	private volatile boolean exceptionCaught = false;
	private final Array<Lwjgl3Window> mainThreadWindows = new Array<>();
	private final Array<Lwjgl3Window> renderThreadWindows = new Array<>();
	private Lwjgl3Window currentWindow;

	private final Array<LifecycleListener> lifecycleListeners = new Array<>();

	private ApplicationLogger applicationLogger;
	private int logLevel = LOG_INFO;

	private static GLFWErrorCallback errorCallback;
	private static Callback glDebugCallback;

	public Lwjgl3Application(ApplicationListener listener, Lwjgl3ApplicationConfiguration config) {
		initializeGlfw();
		setApplicationLogger(new Lwjgl3ApplicationLogger());

		this.config = Lwjgl3ApplicationConfiguration.copy(config);
		if (this.config.title == null) {
			this.config.title = listener.getClass().getSimpleName();
		}

		this.graphics = new Lwjgl3Graphics(this.config);
		this.audio = Gdx.audio = new MockAudio();
		this.files = Gdx.files = new Lwjgl3Files();
		this.net = Gdx.net = new Lwjgl3Net();

		Gdx.app = this;
		Gdx.graphics = graphics;
		Gdx.input = null;

		Lwjgl3Cursor.createSystemCursors();

		Lwjgl3Window primaryWindow = new Lwjgl3Window(listener, config);
		mainThreadWindows.add(primaryWindow);
		renderThreadWindows.add(primaryWindow);

		registerContext(APPLICATION_CONTEXT);
		Lwjgl3Runnables.separateRenderThread = config.separateRenderThread;

		if (separateRenderThread) {
			renderThread = new Thread(this::renderThreadFunction, "gdx-render");
			renderThread.setUncaughtExceptionHandler(this::renderThreadExceptionHandler);
			renderThread.start();

			mainThreadFunction();
		} else {
			renderThread = null;
			renderThreadFunction();
		}
	}

	private void shutdown() {
		glfwMakeContextCurrent(APPLICATION_CONTEXT);
		unregisterContext(APPLICATION_CONTEXT);
		// TODO: audio
		Lwjgl3Cursor.disposeSystemCursors();
		glfwSetErrorCallback(null).free();
		if (glDebugCallback != null) {
			glDebugCallback.free();
		}
		glfwTerminate();
	}

	private void mainThreadFunction() {
		try {

			while (updating && !exceptionCaught) {
				glfwWaitEventsTimeout(1.0);
				executeMainThreadDelegates();

				for (Lwjgl3Window window : mainThreadWindows) {
					executeMainThreadDelegates(window);
				}

				__context_main(APPLICATION_CONTEXT);
			}

			renderThread.join(); // wait for the render thread to complete

		} catch (Throwable t) {
			if (t instanceof RuntimeException)
				throw (RuntimeException) t;
			else
				throw new GdxRuntimeException(t);
		} finally {
			shutdown();
		}
	}

	private void renderThreadFunction() {

		Lwjgl3Window primaryWindow = renderThreadWindows.get(0);
		long primaryWindowHandle = __call_main(0L, context -> primaryWindow.createWindow(0L));

		if (primaryWindowHandle == 0L) {
			throw new GdxRuntimeException("Failed to create primary window.");
		}

		primaryWindow.completeWindow(primaryWindowHandle);
		primaryWindow.notifyNewWindow();

		if (config.debug) {
			glDebugCallback = GLUtil.setupDebugMessageCallback(config.debugStream);
			setGLDebugMessageControl(GLDebugMessageSeverity.NOTIFICATION, false);
		}

		final Array<Lwjgl3Window> closedWindows = new Array<>();

		while (rendering) {

			if (!separateRenderThread) {
				// in single-threaded mode, simulate main thread update
				glfwPollEvents();
				executeMainThreadDelegates();

				for (Lwjgl3Window window : mainThreadWindows) {
					executeMainThreadDelegates(window);
				}

				__context_main(APPLICATION_CONTEXT);
			}

			__context_render(APPLICATION_CONTEXT);

			int numRunnablesExecuted = executeRenderThreadRunnables();
			boolean shouldRequestRendering = numRunnablesExecuted > 0;

			graphics.update();

			closedWindows.clear();

			int numWindowsRendered = 0;
			for (Lwjgl3Window window : renderThreadWindows) {

				if (shouldRequestRendering && !window.continuous) {
					window.requestRendering();
				}

				window.makeCurrent();
				currentWindow = window;

				numRunnablesExecuted = executeRenderThreadRunnables(window);

				if (window.update(numRunnablesExecuted > 0)) {
					numWindowsRendered++;
				}

				if (glfwWindowShouldClose(window.getWindowHandle())) {
					closedWindows.add(window);
				}
			}

			closeWindows(closedWindows);

			if (numWindowsRendered == 0) {
				try {
					// Sleep a few milliseconds in case no rendering was
					// requested with all windows in non-continuous mode.
					Thread.sleep(1000 / config.idleFPS);
				} catch (InterruptedException ignore) {

				}
			}

			rendering &= renderThreadWindows.size > 0;
		}

		closeWindows(renderThreadWindows);
	}

	private void closeWindows(Array<Lwjgl3Window> closedWindows) {
		for (Lwjgl3Window window : closedWindows) {
			int	numWindows = renderThreadWindows.size;
			renderThreadWindows.removeValue(window, true);
			// Lifecycle listener methods have to be called before ApplicationListener methods. The
			// application will be disposed when _all_ windows have been disposed, which is the case,
			// when there is only 1 window left, which is in the process of being disposed.
			if (numWindows == 1) {
				for (int i = lifecycleListeners.size - 1; i >= 0; i--) {
					LifecycleListener l = lifecycleListeners.get(i);
					l.pause();
					l.dispose();
				}
				lifecycleListeners.clear();
			}
			window.dispose();
			__call_main((Void) null, context -> {
				window.disposeWindow();
				mainThreadWindows.removeValue(window, true);
				updating = mainThreadWindows.size > 0;
				return null;
			});
		}
	}

	private void renderThreadExceptionHandler(Thread t, Throwable e) {
		exceptionCaught = true;
		glfwPostEmptyEvent();
		error("Lwjgl3Application", "Exception caught in render thread.", e);
	}

	@Override
	public ApplicationListener getApplicationListener() {
		return currentWindow.getListener();
	}

	@Override
	public Graphics getGraphics() {
		return graphics;
	}

	@Override
	public Audio getAudio() {
		return audio;
	}

	@Override
	public Input getInput() {
		return currentWindow.getInput();
	}

	@Override
	public Files getFiles() {
		return files;
	}

	@Override
	public Net getNet() {
		return net;
	}

	@Override
	public void debug(String tag, String message) {
		if (logLevel >= LOG_DEBUG) {
			applicationLogger.debug(tag, message);
		}
	}

	@Override
	public void debug(String tag, String message, Throwable exception) {
		if (logLevel >= LOG_DEBUG) {
			applicationLogger.debug(tag, message, exception);
		}
	}

	@Override
	public void log(String tag, String message) {
		if (logLevel >= LOG_INFO) {
			applicationLogger.log(tag, message);
		}
	}

	@Override
	public void log(String tag, String message, Throwable exception) {
		if (logLevel >= LOG_INFO) {
			applicationLogger.log(tag, message, exception);
		}
	}

	@Override
	public void error(String tag, String message) {
		if (logLevel >= LOG_ERROR) {
			applicationLogger.error(tag, message);
		}
	}

	@Override
	public void error(String tag, String message, Throwable exception) {
		if (logLevel >= LOG_ERROR) {
			applicationLogger.error(tag, message, exception);
		}
	}

	@Override
	public void setLogLevel(int logLevel) {
		this.logLevel = logLevel;
	}

	@Override
	public int getLogLevel() {
		return logLevel;
	}

	@Override
	public void setApplicationLogger(ApplicationLogger applicationLogger) {
		this.applicationLogger = applicationLogger;
	}

	@Override
	public ApplicationLogger getApplicationLogger() {
		return applicationLogger;
	}

	@Override
	public ApplicationType getType() {
		return ApplicationType.Desktop;
	}

	@Override
	public int getVersion() {
		return 0;
	}

	@Override
	public long getJavaHeap() {
		return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}

	@Override
	public long getNativeHeap() {
		return getJavaHeap();
	}

	@Override
	public Preferences getPreferences(String name) {
		if (!preferences.containsKey(name)) {
			Preferences prefs = new Lwjgl3Preferences(new Lwjgl3FileHandle(
					new File(config.preferencesDirectory, name), config.preferencesFileType));
			preferences.put(name, prefs);
		}
		return preferences.get(name);
	}

	@Override
	public Clipboard getClipboard() {
		return currentWindow.getClipboard();
	}

	@Override
	public void postRunnable(Runnable runnable) {
		__post_render(currentWindow, runnable);
	}

	@Override
	public void exit() {
		rendering = false;
	}

	@Override
	public void addLifecycleListener(LifecycleListener listener) {
		__post_render(() -> lifecycleListeners.add(listener));
	}

	@Override
	public void removeLifecycleListener(LifecycleListener listener) {
		__post_render(() -> lifecycleListeners.removeValue(listener, true));
	}

	/**
	 * Creates a new {@link Lwjgl3Window} using the provided listener and {@link Lwjgl3WindowConfiguration}.
	 */
	public Lwjgl3Window newWindow(ApplicationListener listener, Lwjgl3WindowConfiguration config) {
		Lwjgl3ApplicationConfiguration appConfig = Lwjgl3ApplicationConfiguration.copy(this.config);
		appConfig.setWindowConfiguration(config);
		Lwjgl3Window window = new Lwjgl3Window(listener, appConfig);

		// delay window creation until next frame, so we don't conflict with GL contexts
		__post_render(() -> {
			long sharedContext = renderThreadWindows.get(0).getWindowHandle();
			long windowHandle = __call_main(0L, nil -> {
				mainThreadWindows.add(window);
				return window.createWindow(sharedContext);
			});
			if (windowHandle == 0) {
				throw new GdxRuntimeException("Failed to create GLFW window.");
			}
			window.completeWindow(windowHandle);
			renderThreadWindows.add(window);
			window.notifyNewWindow();
		});

		return window;
	}

	static void initializeGlfw() {
		if (errorCallback == null) {
			Lwjgl3NativesLoader.load();
			errorCallback = GLFWErrorCallback.createPrint(System.err);
			glfwSetErrorCallback(errorCallback);
			if (!glfwInit()) {
				throw new GdxRuntimeException("Failed to initialize GLFW");
			}
			Lwjgl3Graphics.enumerateMonitorsAndDisplayModes();
		}
	}

	public enum GLDebugMessageSeverity {
		HIGH(
				GL43.GL_DEBUG_SEVERITY_HIGH,
				KHRDebug.GL_DEBUG_SEVERITY_HIGH,
				ARBDebugOutput.GL_DEBUG_SEVERITY_HIGH_ARB,
				AMDDebugOutput.GL_DEBUG_SEVERITY_HIGH_AMD),
		MEDIUM(
				GL43.GL_DEBUG_SEVERITY_MEDIUM,
				KHRDebug.GL_DEBUG_SEVERITY_MEDIUM,
				ARBDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_ARB,
				AMDDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_AMD),
		LOW(
				GL43.GL_DEBUG_SEVERITY_LOW,
				KHRDebug.GL_DEBUG_SEVERITY_LOW,
				ARBDebugOutput.GL_DEBUG_SEVERITY_LOW_ARB,
				AMDDebugOutput.GL_DEBUG_SEVERITY_LOW_AMD),
		NOTIFICATION(
				GL43.GL_DEBUG_SEVERITY_NOTIFICATION,
				KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION,
				-1,
				-1);

		private final int gl43, khr, arb, amd;

		GLDebugMessageSeverity(int gl43, int khr, int arb, int amd) {
			this.gl43 = gl43;
			this.khr = khr;
			this.arb = arb;
			this.amd = amd;
		}
	}

	/**
	 * Enables or disables GL debug messages for the specified severity level. Returns false if the severity
	 * level could not be set (e.g. the NOTIFICATION level is not supported by the ARB and AMD extensions).
	 * <p>
	 * See {@link Lwjgl3ApplicationConfiguration#enableGLDebugOutput(boolean, PrintStream)}
	 */
	public static boolean setGLDebugMessageControl(GLDebugMessageSeverity severity, boolean enabled) {
		GLCapabilities caps = GL.getCapabilities();
		final int GL_DONT_CARE = 0x1100; // not defined anywhere yet

		if (caps.OpenGL43) {
			GL43.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, severity.gl43, (IntBuffer) null, enabled);
			return true;
		}

		if (caps.GL_KHR_debug) {
			KHRDebug.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, severity.khr, (IntBuffer) null, enabled);
			return true;
		}

		if (caps.GL_ARB_debug_output && severity.arb != -1) {
			ARBDebugOutput.glDebugMessageControlARB(GL_DONT_CARE, GL_DONT_CARE, severity.arb, (IntBuffer) null, enabled);
			return true;
		}

		if (caps.GL_AMD_debug_output && severity.amd != -1) {
			AMDDebugOutput.glDebugMessageEnableAMD(GL_DONT_CARE, severity.amd, (IntBuffer) null, enabled);
			return true;
		}

		return false;
	}

}
