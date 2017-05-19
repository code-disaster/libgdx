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
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.utils.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static com.badlogic.gdx.Graphics.BufferFormat;
import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.HdpiMode;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.opengl.GL11.*;

public class Lwjgl3Window extends Lwjgl3Runnables implements Disposable {

	private final ApplicationListener listener;
	private final Lwjgl3ApplicationConfiguration config;
	private final Lwjgl3Graphics graphics;
	private final Lwjgl3Input input;
	private final Lwjgl3Clipboard clipboard;

	private volatile long handle;
	private Lwjgl3WindowListener windowListener;

	private boolean iconified = false;
	boolean continuous = true;
	private volatile boolean requestRendering = false;
	BufferFormat bufferFormat;
	int backBufferWidth, backBufferHeight;
	int logicalWidth, logicalHeight;
	int positionX, positionY;

	private final GLFWWindowFocusCallback focusCallback = new GLFWWindowFocusCallback() {
		@Override
		public void invoke(long window, boolean focused) {
			postRenderThreadRunnable(() -> {
				if (focused) {
					windowListener.focusGained();
				} else {
					windowListener.focusLost();
				}
			});
		}
	};

	private final GLFWWindowIconifyCallback iconifyCallback = new GLFWWindowIconifyCallback() {
		@Override
		public void invoke(long window, boolean iconified) {
			postRenderThreadRunnable(() -> {
				windowListener.iconified(iconified);
				Lwjgl3Window.this.iconified = iconified;
				if (iconified) {
					listener.pause();
				} else {
					listener.resume();
				}
			});
		}
	};

	private final GLFWWindowMaximizeCallback maximizeCallback = new GLFWWindowMaximizeCallback() {
		@Override
		public void invoke(long window, boolean maximized) {
			postRenderThreadRunnable(() -> {
				windowListener.maximized(maximized);
			});
		}
	};

	private final GLFWWindowCloseCallback closeCallback = new GLFWWindowCloseCallback() {
		@Override
		public void invoke(long window) {
			postRenderThreadRunnable(() -> {
				if (windowListener.closeRequested()) {
					glfwSetWindowShouldClose(window, false);
				}
			});
		}
	};

	private final GLFWDropCallback dropCallback = new GLFWDropCallback() {
		@Override
		public void invoke(long window, int count, long names) {
			final String[] files = new String[count];
			for (int i = 0; i < count; i++) {
				files[i] = getName(names, i);
			}
			postRenderThreadRunnable(() -> {
				windowListener.filesDropped(files);
			});
		}
	};

	private final GLFWWindowRefreshCallback refreshCallback = new GLFWWindowRefreshCallback() {
		@Override
		public void invoke(long window) {
			postRenderThreadRunnable(() -> {
				windowListener.refreshRequested();
				Lwjgl3Window.this.requestRendering();
			});
		}
	};

	private final GLFWFramebufferSizeCallback resizeCallback = new GLFWFramebufferSizeCallback() {
		@Override
		public void invoke(long window, int width, int height) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer x = stack.callocInt(1);
				IntBuffer y = stack.callocInt(1);
				glfwGetFramebufferSize(window, x, y);
				final int backBufferWidth = x.get(0);
				final int backBufferHeight = y.get(0);
				glfwGetWindowSize(window, x, y);
				final int logicalWidth = x.get(0);
				final int logicalHeight = y.get(0);
				postRenderThreadRunnable(() -> {
					listener.resize(width, height);
					Lwjgl3Window.this.backBufferWidth = backBufferWidth;
					Lwjgl3Window.this.backBufferHeight = backBufferHeight;
					Lwjgl3Window.this.logicalWidth = logicalWidth;
					Lwjgl3Window.this.logicalHeight = logicalHeight;
					// TODO: old version calls glViewport() and glfwSwapBuffers()
				});
			}
		}
	};

	private final GLFWWindowPosCallback positionCallback = new GLFWWindowPosCallback() {
		@Override
		public void invoke(long window, int xpos, int ypos) {
			postRenderThreadRunnable(() -> {
				positionX = xpos;
				positionY = ypos;
			});
		}
	};

	Lwjgl3Window(ApplicationListener listener, Lwjgl3ApplicationConfiguration config) {
		this.listener = listener;
		this.config = config;
		this.graphics = (Lwjgl3Graphics) Gdx.graphics;
		this.input = new Lwjgl3Input(this);
		this.clipboard = new Lwjgl3Clipboard(this);
		setWindowListener(config.windowListener);
	}

	long createWindow(long sharedContext) {
		// Window creation and installation of callback handlers must be done in the main thread.
		// This is a blocking call to keep synchronization simple.
		long handle = createGlfwWindow(sharedContext);
		glfwSetWindowFocusCallback(handle, focusCallback);
		glfwSetWindowIconifyCallback(handle, iconifyCallback);
		glfwSetWindowMaximizeCallback(handle, maximizeCallback);
		glfwSetWindowCloseCallback(handle, closeCallback);
		glfwSetDropCallback(handle, dropCallback);
		glfwSetWindowRefreshCallback(handle, refreshCallback);
		glfwSetFramebufferSizeCallback(handle, resizeCallback);
		glfwSetWindowPosCallback(handle, positionCallback);
		input.setupCallbacks(handle);

		// fill cached position/size data
		// after this, they are only updated through callback handlers
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer x = stack.callocInt(1);
			IntBuffer y = stack.callocInt(1);
			glfwGetFramebufferSize(handle, x, y);
			backBufferWidth = x.get(0);
			backBufferHeight = y.get(0);
			glfwGetWindowSize(handle, x, y);
			logicalWidth = x.get(0);
			logicalHeight = y.get(0);
			glfwGetWindowPos(handle, x, y);
			positionX = x.get(0);
			positionY = y.get(0);
		}

		if (config.initialVisible) {
			glfwShowWindow(handle);
		}

		return handle;
	}

	void completeWindow(long windowHandle) {
		finalizeGLFWWindow(windowHandle);

		for (int i = 0; i < 2; i++) {
			glClearColor(config.initialBackgroundColor.r, config.initialBackgroundColor.g,
					config.initialBackgroundColor.b, config.initialBackgroundColor.a);
			glClear(GL_COLOR_BUFFER_BIT);
			glfwSwapBuffers(windowHandle);
		}

		handle = windowHandle;

		bufferFormat = new BufferFormat(config.r, config.g, config.b, config.a,
				config.depth, config.stencil, config.samples, false);

		makeCurrent();

		windowListener.created(this);

		listener.create();
		listener.resize(getWidth(), getHeight());
	}

	@Override
	public void dispose() {
		makeCurrent();
		listener.pause();
		listener.dispose();
		try {
			// Destroying a window is again done with a blocking operation,
			// so we can be safe to continue.
			postMainThreadRunnable(() -> {
				input.dispose();
				focusCallback.free();
				iconifyCallback.free();
				maximizeCallback.free();
				closeCallback.free();
				dropCallback.free();
				refreshCallback.free();
				resizeCallback.free();
				positionCallback.free();
				glfwDestroyWindow(handle);
				return null;
			});
		} catch (InterruptedException e) {
			Gdx.app.error("Lwjgl3Application", "Exception while destroying window", e);
		}
	}

	/**
	 * @return the {@link ApplicationListener} associated with this window
	 **/
	public ApplicationListener getListener() {
		return listener;
	}

	/**
	 * @return the {@link Lwjgl3WindowListener} set on this window
	 **/
	public Lwjgl3WindowListener getWindowListener() {
		return windowListener;
	}

	/**
	 * Sets a new {@link Lwjgl3WindowListener} on this window.
	 */
	public void setWindowListener(Lwjgl3WindowListener listener) {
		this.windowListener = listener != null ? listener : new Lwjgl3WindowAdapter();
	}

	Lwjgl3ApplicationConfiguration getConfig() {
		return config;
	}

	public long getWindowHandle() {
		return handle;
	}

	Lwjgl3Input getInput() {
		return input;
	}

	Clipboard getClipboard() {
		return clipboard;
	}

	void makeCurrent() {
		graphics.makeCurrent(this);
		Gdx.input = input;

		glfwMakeContextCurrent(handle);
	}

	boolean update() {

		int numRunnablesExecuted = executeRenderThreadRunnables();
		boolean shouldRender = numRunnablesExecuted > 0 || continuous;

		if (!iconified) {
			input.update();
		}

		shouldRender |= requestRendering && !iconified;
		requestRendering = false;

		if (shouldRender) {
			listener.render();
			glfwSwapBuffers(handle);
		}

		if (!iconified) {
			input.prepareNext();
		}

		return shouldRender;
	}

	void requestRendering() {
		requestRendering = true;
	}

	/**
	 * Sets the position of the window in logical coordinates. All monitors
	 * span a virtual surface together. The coordinates are relative to
	 * the first monitor in the virtual surface.
	 **/
	public void setPosition(int x, int y) {
		postMainThreadRunnable(() -> glfwSetWindowPos(handle, x, y));
	}

	/**
	 * @return the window position in logical coordinates. All monitors
	 * span a virtual surface together. The coordinates are relative to
	 * the first monitor in the virtual surface.
	 **/
	public int getPositionX() {
		return positionX;
	}

	/**
	 * @return the window position in logical coordinates. All monitors
	 * span a virtual surface together. The coordinates are relative to
	 * the first monitor in the virtual surface.
	 **/
	public int getPositionY() {
		return positionY;
	}

	int getWidth() {
		return config.hdpiMode == HdpiMode.Pixels ? backBufferWidth : logicalWidth;
	}

	int getHeight() {
		return config.hdpiMode == HdpiMode.Pixels ? backBufferHeight : logicalHeight;
	}

	/**
	 * Sets the visibility of the window. Invisible windows will still
	 * call their {@link ApplicationListener}
	 */
	public void setVisible(boolean visible) {
		postMainThreadRunnable(() -> {
			if (visible) {
				glfwShowWindow(handle);
			} else {
				glfwHideWindow(handle);
			}
		});
	}

	/**
	 * Closes this window and pauses and disposes the associated
	 * {@link ApplicationListener}.
	 */
	public void closeWindow() {
		glfwSetWindowShouldClose(handle, true);
	}

	/**
	 * Minimizes (iconifies) the window. Iconified windows do not call
	 * their {@link ApplicationListener} until the window is restored.
	 */
	public void iconifyWindow() {
		postMainThreadRunnable(() -> glfwIconifyWindow(handle));
	}

	/**
	 * De-minimizes (de-iconifies) and de-maximizes the window.
	 */
	public void restoreWindow() {
		postMainThreadRunnable(() -> glfwRestoreWindow(handle));
	}

	/**
	 * Maximizes the window.
	 */
	public void maximizeWindow() {
		postMainThreadRunnable(() -> glfwMaximizeWindow(handle));
	}

	void setTitle(CharSequence title) {
		postMainThreadRunnable(() -> glfwSetWindowTitle(handle, title));
	}

	void setUndecorated(boolean undecorated) {
		config.setDecorated(!undecorated);
		postMainThreadRunnable(() -> glfwSetWindowAttrib(handle, GLFW_DECORATED, undecorated ? GLFW_FALSE : GLFW_TRUE));
	}

	void setResizable(boolean resizable) {
		config.setResizable(resizable);
		postMainThreadRunnable(() -> glfwSetWindowAttrib(handle, GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE));
	}

	boolean setFullscreenMode(Lwjgl3DisplayMode displayMode) {
		try {
			input.reset();
			final int x = positionX;
			final int y = positionY;
			return postMainThreadRunnable(() -> {
				if (config.fullscreenMode == null) {
					config.setWindowPosition(x, y);
					glfwSetWindowMonitor(handle, displayMode.getMonitorHandle(),
							0, 0, displayMode.width, displayMode.height, displayMode.refreshRate);
				} else {
					Lwjgl3DisplayMode currentMode = config.fullscreenMode;
					if (currentMode.getMonitorHandle() == displayMode.getMonitorHandle()
							&& currentMode.refreshRate == displayMode.refreshRate) {
						// same monitor and refresh rate
						glfwSetWindowSize(handle, displayMode.width, displayMode.height);
					} else {
						// different monitor and/or refresh rate
						glfwSetWindowMonitor(handle, displayMode.getMonitorHandle(),
								0, 0, displayMode.width, displayMode.height, displayMode.refreshRate);
					}
				}
				return true;
			});
		} catch (InterruptedException e) {
			Gdx.app.error("Lwjgl3Application", "Exception while switching to fullscreen mode", e);
			return false;
		}
	}

	boolean setWindowedMode(int width, int height) {
		try {
			input.reset();
			final int x = positionX;
			final int y = positionY;
			return postMainThreadRunnable(() -> {
				if (config.fullscreenMode == null) {
					glfwSetWindowSize(handle, width, height);
				} else {
					glfwSetWindowMonitor(handle, 0, x, y, width, height, GLFW_DONT_CARE);
					config.fullscreenMode = null;
				}
				config.setWindowedMode(width, height);
				return true;
			});
		} catch (InterruptedException e) {
			Gdx.app.error("Lwjgl3Application", "Exception while switching to windowed mode", e);
			return false;
		}
	}

	boolean isFullscreen() {
		return config.fullscreenMode != null;
	}

	/**
	 * Sets the icon that will be used in the window's title bar. Has no effect in macOS, which doesn't use window icons.
	 * <p>
	 * This function blocks the render thread until the operation has been processed by the main thread.
	 *
	 * @param images One or more images. The one closest to the system's desired size will be scaled. Good sizes include
	 *               16x16, 32x32 and 48x48. The chosen image is copied, and the provided Pixmaps are not disposed.
	 * @see GLFW#glfwSetWindowIcon(long, GLFWImage.Buffer)
	 */
	public void setIcon(Pixmap... images) {
		try {
			postMainThreadRunnable(() -> {
				setIcon(handle, images);
				return null;
			});
		} catch (InterruptedException e) {
			Gdx.app.error("Lwjgl3Application", "Exception while setting window icons", e);
		}
	}

	private void setIcon(long windowHandle, String[] imagePaths, Files.FileType imageFileType) {
		if (SharedLibraryLoader.isMac) {
			return;
		}

		Pixmap[] pixmaps = new Pixmap[imagePaths.length];
		for (int i = 0; i < imagePaths.length; i++) {
			pixmaps[i] = new Pixmap(Gdx.files.getFileHandle(imagePaths[i], imageFileType));
		}

		setIcon(windowHandle, pixmaps);

		for (Pixmap pixmap : pixmaps) {
			pixmap.dispose();
		}
	}

	private void setIcon(long windowHandle, Pixmap[] images) {
		if (SharedLibraryLoader.isMac) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {

			GLFWImage.Buffer buffer = GLFWImage.mallocStack(images.length, stack);

			for (Pixmap image : images) {
				GLFWImage icon = GLFWImage.mallocStack(stack);
				icon.set(image.getWidth(), image.getHeight(), image.getPixels());
				buffer.put(icon);
			}

			buffer.position(0);
			glfwSetWindowIcon(windowHandle, buffer);
		}
	}

	/**
	 * Sets minimum and maximum size limits for the window. If the window is full screen
	 * or not resizable, these limits are ignored. Use -1 to indicate an unrestricted dimension.
	 */
	public void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
		postMainThreadRunnable(() -> setSizeLimits(handle, minWidth, minHeight, maxWidth, maxHeight));
	}

	private static void setSizeLimits(long windowHandle, int minWidth, int minHeight, int maxWidth, int maxHeight) {
		glfwSetWindowSizeLimits(windowHandle,
				minWidth > -1 ? minWidth : GLFW.GLFW_DONT_CARE,
				minHeight > -1 ? minHeight : GLFW.GLFW_DONT_CARE,
				maxWidth > -1 ? maxWidth : GLFW.GLFW_DONT_CARE,
				maxHeight > -1 ? maxHeight : GLFW.GLFW_DONT_CARE);
	}

	/**
	 * Creates a GLFW window. This function is called from the main thread.
	 */
	private long createGlfwWindow(long sharedContext) {
		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
		glfwWindowHint(GLFW_RESIZABLE, config.windowResizable ? GLFW_TRUE : GLFW_FALSE);
		glfwWindowHint(GLFW_MAXIMIZED, config.windowMaximized ? GLFW_TRUE : GLFW_FALSE);

		if (sharedContext == 0) {
			glfwWindowHint(GLFW_RED_BITS, config.r);
			glfwWindowHint(GLFW_GREEN_BITS, config.g);
			glfwWindowHint(GLFW_BLUE_BITS, config.b);
			glfwWindowHint(GLFW_ALPHA_BITS, config.a);
			glfwWindowHint(GLFW_STENCIL_BITS, config.stencil);
			glfwWindowHint(GLFW_DEPTH_BITS, config.depth);
			glfwWindowHint(GLFW_SAMPLES, config.samples);
		} else {
			glfwMakeContextCurrent(0L);
		}

		if (config.useGL30) {
			glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, config.gles30ContextMajorVersion);
			glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, config.gles30ContextMinorVersion);
			if (SharedLibraryLoader.isMac) {
				// hints mandatory on OS X for GL 3.2+ context creation, but fail on Windows if the
				// WGL_ARB_create_context extension is not available
				// see: http://www.glfw.org/docs/latest/compat.html
				glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
				glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
			}
		}

		if (config.debug) {
			glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
		}

		long windowHandle;
		if (config.fullscreenMode != null) {
			// glfwWindowHint(GLFW.GLFW_REFRESH_RATE, config.fullscreenMode.refreshRate);
			windowHandle = glfwCreateWindow(config.fullscreenMode.width, config.fullscreenMode.height,
					config.title, config.fullscreenMode.getMonitorHandle(), sharedContext);
		} else {
			glfwWindowHint(GLFW_DECORATED, config.windowDecorated ? GLFW_TRUE : GLFW_FALSE);
			windowHandle = glfwCreateWindow(config.windowWidth, config.windowHeight,
					config.title, 0, sharedContext);
		}
		if (windowHandle == 0) {
			throw new GdxRuntimeException("Couldn't create window");
		}

		setSizeLimits(windowHandle, config.windowMinWidth, config.windowMinHeight,
				config.windowMaxWidth, config.windowMaxHeight);

		if (config.fullscreenMode == null && !config.windowMaximized) {
			if (config.windowX == -1 && config.windowY == -1) {
				int windowWidth = Math.max(config.windowWidth, config.windowMinWidth);
				int windowHeight = Math.max(config.windowHeight, config.windowMinHeight);
				if (config.windowMaxWidth > -1) windowWidth = Math.min(windowWidth, config.windowMaxWidth);
				if (config.windowMaxHeight > -1) windowHeight = Math.min(windowHeight, config.windowMaxHeight);
				GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
				glfwSetWindowPos(windowHandle, vidMode.width() / 2 - windowWidth / 2, vidMode.height() / 2 - windowHeight / 2);
			} else {
				glfwSetWindowPos(windowHandle, config.windowX, config.windowY);
			}
		}
		if (config.windowIconPaths != null) {
			setIcon(windowHandle, config.windowIconPaths, config.windowIconFileType);
		}

		return windowHandle;
	}

	/**
	 * This function completes window creation by setting up the GL context. It is called from the render thread.
	 */
	private void finalizeGLFWWindow(long windowHandle) {
		glfwMakeContextCurrent(windowHandle);
		glfwSwapInterval(config.vSyncEnabled ? 1 : 0);

		GL.createCapabilities();
		GLVersion glVersion = Gdx.graphics.getGLVersion();

		if (!glVersion.isVersionEqualToOrHigher(2, 0))
			throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
					+ glGetString(GL_VERSION) + "\n" + glVersion.getDebugVersionString());

		if (!supportsFBO(glVersion)) {
			throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
					+ glGetString(GL_VERSION) + ", FBO extension: false\n" + glVersion.getDebugVersionString());
		}
	}

	private boolean supportsFBO(GLVersion glVersion) {
		// FBO is in core since OpenGL 3.0, see https://www.opengl.org/wiki/Framebuffer_Object
		return glVersion.isVersionEqualToOrHigher(3, 0)
				|| glfwExtensionSupported("GL_EXT_framebuffer_object")
				|| glfwExtensionSupported("GL_ARB_framebuffer_object");
	}

}
