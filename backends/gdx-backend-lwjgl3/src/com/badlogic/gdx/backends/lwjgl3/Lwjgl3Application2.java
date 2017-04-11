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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;

public class Lwjgl3Application2 implements Application {

	private final Lwjgl3ApplicationConfiguration config;

	private final Audio audio;
	private final Files files;
	private final Net net;
	private final ObjectMap<String, Preferences> preferences = new ObjectMap<>();

	private final Thread renderThread;
	private volatile boolean rendering = false;
	private volatile boolean exceptionCaught = false;
	private final Array<Runnable> renderThreadRunnables = new Array<>();

	private final Array<Runnable> mainThreadRunnables = new Array<>();

	private final Array<LifecycleListener> lifecycleListeners = new Array<>();

	private ApplicationLogger applicationLogger;
	private int logLevel = LOG_INFO;

	public Lwjgl3Application2(ApplicationListener listener, Lwjgl3ApplicationConfiguration config) {
		initializeGLFW();
		setApplicationLogger(new Lwjgl3ApplicationLogger());

		this.config = Lwjgl3ApplicationConfiguration.copy(config);
		if (this.config.title == null) {
			this.config.title = listener.getClass().getSimpleName();
		}

		Gdx.app = this;

		this.audio = Gdx.audio = new MockAudio();
		this.files = Gdx.files = new Lwjgl3Files();
		this.net = Gdx.net = new Lwjgl3Net();

		Lwjgl3Cursor2.createSystemCursors();

		renderThread = new Thread(this::renderThreadFunction, "gdx-render");
		renderThread.setUncaughtExceptionHandler(this::renderThreadExceptionHandler);
		renderThread.start();

		mainThreadFunction();
	}

	private void mainThreadFunction() {
		try {
			boolean shouldExit = false;

			while (!shouldExit) {
				glfwWaitEvents();
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

	private void shutdown() {
		Lwjgl3Cursor2.disposeSystemCursors();
		errorCallback.free();
		glfwTerminate();
	}

	private void renderThreadFunction() {

	}

	private void renderThreadExceptionHandler(Thread t, Throwable e) {
		exceptionCaught = true;
		glfwPostEmptyEvent();
		log("Lwjgl3Application", "Exception caught in render thread.");
	}

	@Override
	public ApplicationListener getApplicationListener() {
		return null;
	}

	@Override
	public Graphics getGraphics() {
		return null;
	}

	@Override
	public Audio getAudio() {
		return audio;
	}

	@Override
	public Input getInput() {
		return null;
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
		return null;
	}

	@Override
	public Clipboard getClipboard() {
		return null;
	}

	@Override
	public void postRunnable(Runnable runnable) {
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.add(runnable);
		}
	}

	/**
	 * Queues a non-blocking function for execution in the main thread.
	 */
	void postMainThreadRunnable(Runnable runnable) {
		synchronized (mainThreadRunnables) {
			mainThreadRunnables.add(runnable);
			glfwPostEmptyEvent();
		}
	}

	/**
	 * Queues a blocking function for execution in the main thread.
	 * <p>
	 * Uses a {@link CountDownLatch} to wait for execution. The calling thread (which should be the render thread
	 * in most use cases) blocks until the function result is available.
	 * <p>
	 * Throws an {@link InterruptedException} in case of an error.
	 */
	<R> R postMainThreadRunnable(Supplier<R> runnable) throws InterruptedException {
		AtomicReference<R> result = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		synchronized (mainThreadRunnables) {
			mainThreadRunnables.add(() -> {
				result.set(runnable.get());
				latch.countDown();
			});
			glfwPostEmptyEvent();
		}
		latch.await();
		return result.get();
	}

	@Override
	public void exit() {
		rendering = false;
	}

	@Override
	public void addLifecycleListener(LifecycleListener listener) {
		postRunnable(() -> lifecycleListeners.add(listener));
	}

	@Override
	public void removeLifecycleListener(LifecycleListener listener) {
		postRunnable(() -> lifecycleListeners.removeValue(listener, true));
	}

	private static GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);

	static void initializeGLFW() {
		if (errorCallback == null) {
			Lwjgl3NativesLoader.load();
			errorCallback = GLFWErrorCallback.createPrint(System.err);
			glfwSetErrorCallback(errorCallback);
			if (!glfwInit()) {
				throw new GdxRuntimeException("Failed to initialize GLFW");
			}
		}
	}

}
