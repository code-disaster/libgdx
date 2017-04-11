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
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static com.badlogic.gdx.Graphics.BufferFormat;
import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.HdpiMode;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

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
			__post_render(window, () -> {
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
			__post_render(window, () -> {
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
			__post_render(window, () -> windowListener.maximized(maximized));
		}
	};

	private final GLFWWindowCloseCallback closeCallback = new GLFWWindowCloseCallback() {
		@Override
		public void invoke(long window) {
			glfwSetWindowShouldClose(window, false);
			__post_render(window, () -> {
				if (windowListener.closeRequested()) {
					glfwSetWindowShouldClose(handle, true);
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
			__post_render(window, () -> windowListener.filesDropped(files));
		}
	};

	private final GLFWWindowRefreshCallback refreshCallback = new GLFWWindowRefreshCallback() {
		@Override
		public void invoke(long window) {
			__post_render(window, () -> {
				windowListener.refreshRequested();
				Lwjgl3Window.this.requestRendering();
			});
		}
	};

	private final GLFWWindowSizeCallback windowSizeCallback = new GLFWWindowSizeCallback() {
		@Override
		public void invoke(long window, int width, int height) {
			__post_render(window, () -> {
				Lwjgl3Window.this.logicalWidth = width;
				Lwjgl3Window.this.logicalHeight = height;
				// done here because GLFW sends FramebufferSizeCallback first, WindowSizeCallback second
				// use getters to pass size based on HdpiMode
				listener.resize(getWidth(), getHeight());
				// TODO: old version calls glViewport() and glfwSwapBuffers()
			});
		}
	};

	private final GLFWFramebufferSizeCallback framebufferSizeCallback = new GLFWFramebufferSizeCallback() {
		@Override
		public void invoke(long window, int width, int height) {
			__post_render(window, () -> {
				Lwjgl3Window.this.backBufferWidth = width;
				Lwjgl3Window.this.backBufferHeight = height;
			});
		}
	};

	private final GLFWWindowPosCallback positionCallback = new GLFWWindowPosCallback() {
		@Override
		public void invoke(long window, int xpos, int ypos) {
			__post_render(window, () -> {
				positionX = xpos;
				positionY = ypos;
				windowListener.moved(xpos, ypos);
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
		registerContext(handle);

		glfwSetWindowFocusCallback(handle, focusCallback);
		glfwSetWindowIconifyCallback(handle, iconifyCallback);
		glfwSetWindowMaximizeCallback(handle, maximizeCallback);
		glfwSetWindowCloseCallback(handle, closeCallback);
		glfwSetDropCallback(handle, dropCallback);
		glfwSetWindowRefreshCallback(handle, refreshCallback);
		glfwSetWindowSizeCallback(handle, windowSizeCallback);
		glfwSetFramebufferSizeCallback(handle, framebufferSizeCallback);
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
	}

	void notifyNewWindow() {
		makeCurrent();

		windowListener.created(this);

		listener.create();
		listener.resize(getWidth(), getHeight());
	}

	void disposeWindow() {
		input.disposeCallbacks(handle);
		glfwSetWindowFocusCallback(handle, null);
		glfwSetWindowIconifyCallback(handle, null);
		glfwSetWindowMaximizeCallback(handle, null);
		glfwSetWindowCloseCallback(handle, null);
		glfwSetDropCallback(handle, null);
		glfwSetWindowRefreshCallback(handle, null);
		glfwSetWindowSizeCallback(handle, null);
		glfwSetFramebufferSizeCallback(handle, null);
		glfwSetWindowPosCallback(handle, null);

		glfwSetCursor(handle, NULL);

		unregisterContext(handle);
		glfwDestroyWindow(handle);

		//glfwPollEvents();

		input.dispose();
		focusCallback.free();
		iconifyCallback.free();
		maximizeCallback.free();
		closeCallback.free();
		dropCallback.free();
		refreshCallback.free();
		windowSizeCallback.free();
		framebufferSizeCallback.free();
		positionCallback.free();
	}

	@Override
	public void dispose() {
		makeCurrent();
		listener.pause();
		listener.dispose();
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

		__context_render(handle);

		if (GL11.GL_NO_ERROR != GL11.glGetError()) {
			int x = 0;
		}
	}

	boolean update(boolean shouldRender) {

		shouldRender |= continuous;

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
		__post_main(handle, context -> glfwSetWindowPos(context, x, y));
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
		__post_main(handle, context -> {
			if (visible) {
				glfwShowWindow(context);
			} else {
				glfwHideWindow(context);
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
		__post_main(handle, GLFW::glfwIconifyWindow);
	}

	/**
	 * De-minimizes (de-iconifies) and de-maximizes the window.
	 */
	public void restoreWindow() {
		__post_main(handle, GLFW::glfwRestoreWindow);
	}

	/**
	 * Maximizes the window.
	 */
	public void maximizeWindow() {
		__post_main(handle, GLFW::glfwMaximizeWindow);
	}

	public void setTitle(CharSequence title) {
		__post_main(handle, context -> glfwSetWindowTitle(context, title));
	}

	void setUndecorated(boolean undecorated) {
		config.setDecorated(!undecorated);
		__post_main(handle, context -> glfwSetWindowAttrib(context,
				GLFW_DECORATED, undecorated ? GLFW_FALSE : GLFW_TRUE));
	}

	void setResizable(boolean resizable) {
		config.setResizable(resizable);
		__post_main(handle, context -> glfwSetWindowAttrib(context,
				GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE));
	}

	boolean setFullscreenMode(Lwjgl3DisplayMode displayMode) {
		input.reset();
		final int x = positionX;
		final int y = positionY;
		return __call_main(this, false, context -> {
			if (config.fullscreenMode == null) {
				config.setWindowPosition(x, y);
				glfwSetWindowMonitor(context, displayMode.getMonitorHandle(),
						0, 0, displayMode.width, displayMode.height, displayMode.refreshRate);
			} else {
				Lwjgl3DisplayMode currentMode = config.fullscreenMode;
				if (currentMode.getMonitorHandle() == displayMode.getMonitorHandle()
						&& currentMode.refreshRate == displayMode.refreshRate) {
					// same monitor and refresh rate
					glfwSetWindowSize(context, displayMode.width, displayMode.height);
				} else {
					// different monitor and/or refresh rate
					glfwSetWindowMonitor(context, displayMode.getMonitorHandle(),
							0, 0, displayMode.width, displayMode.height, displayMode.refreshRate);
				}
			}
			return true;
		});
	}

	boolean setWindowedMode(int width, int height) {
		input.reset();
		final int x = positionX;
		final int y = positionY;
		return __call_main(this, false, context -> {
			if (config.fullscreenMode == null) {
				glfwSetWindowSize(context, width, height);
			} else {
				glfwSetWindowMonitor(context, 0, x, y, width, height, GLFW_DONT_CARE);
				config.fullscreenMode = null;
			}
			config.setWindowedMode(width, height);
			return true;
		});
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
		__call_main(this, null, context -> {
			setIcon(context, images);
			return null;
		});
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
		__post_main(handle, context -> setSizeLimits(context, minWidth, minHeight, maxWidth, maxHeight));
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

		windowListener.configure();

		long windowHandle;
		if (config.fullscreenMode != null) {
			glfwWindowHint(GLFW_REFRESH_RATE, config.fullscreenMode.refreshRate);
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
		__context_render(windowHandle);
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
