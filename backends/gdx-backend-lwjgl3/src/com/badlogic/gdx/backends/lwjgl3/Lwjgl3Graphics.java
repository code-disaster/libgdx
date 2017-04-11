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
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.utils.Disposable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

public class Lwjgl3Graphics implements Graphics, Disposable {

	private GL20 gl20;
	private GL30 gl30;
	private GLVersion glVersion;

	private long lastFrameTime = Long.MIN_VALUE;
	private float deltaTime;
	private long frameId;
	private long frameCounterStart = 0;
	private int frames;
	private int fps;

	private Lwjgl3Window currentWindow;

	static Lwjgl3Monitor primaryMonitor;
	static Lwjgl3DisplayMode primaryMonitorDisplayMode;
	static Lwjgl3Monitor[] monitors;

	Lwjgl3Graphics(Lwjgl3ApplicationConfiguration config) {
		if (config.useGL30) {
			gl30 = new Lwjgl3GL30();
			gl20 = gl30;
		} else {
			gl30 = null;
			gl20 = new Lwjgl3GL20();
		}
		Gdx.gl30 = gl30;
		Gdx.gl20 = gl20;
		Gdx.gl = gl30 != null ? gl30 : gl20;
	}

	void update() {

		long time = System.nanoTime();

		if (lastFrameTime == Long.MIN_VALUE) {
			lastFrameTime = time;
		}

		deltaTime = (time - lastFrameTime) / 1000000000.0f;
		lastFrameTime = time;

		if (time - frameCounterStart >= 1000000000L) {
			fps = frames;
			frames = 0;
			frameCounterStart = time;
		}

		frames++;
		frameId++;
	}

	void makeCurrent(Lwjgl3Window window) {
		currentWindow = window;
	}

	public Lwjgl3Window getWindow() {
		return currentWindow;
	}

	@Override
	public boolean isGL30Available() {
		return gl30 != null;
	}

	@Override
	public GL20 getGL20() {
		return gl20;
	}

	@Override
	public GL30 getGL30() {
		return gl30;
	}

	@Override
	public void setGL20 (GL20 gl20) {
		this.gl20 = gl20;
	}

	@Override
	public void setGL30 (GL30 gl30) {
		this.gl30 = gl30;
	}

	@Override
	public int getWidth() {
		return currentWindow.getWidth();
	}

	@Override
	public int getHeight() {
		return currentWindow.getHeight();
	}

	@Override
	public int getBackBufferWidth() {
		return currentWindow.backBufferWidth;
	}

	@Override
	public int getBackBufferHeight() {
		return currentWindow.backBufferHeight;
	}

	public int getLogicalWidth() {
		return currentWindow.logicalWidth;
	}

	public int getLogicalHeight() {
		return currentWindow.logicalHeight;
	}

	@Override
	public long getFrameId() {
		return frameId;
	}

	@Override
	public float getDeltaTime() {
		return deltaTime;
	}

	@Override
	public float getRawDeltaTime() {
		return deltaTime;
	}

	@Override
	public int getFramesPerSecond() {
		return fps;
	}

	@Override
	public GraphicsType getType() {
		return GraphicsType.LWJGL3;
	}

	@Override
	public GLVersion getGLVersion() {
		if (glVersion == null) {
			glVersion = buildGLVersion();
		}
		return glVersion;
	}

	@Override
	public float getPpiX() {
		return getPpcX() / 0.393701f;
	}

	@Override
	public float getPpiY() {
		return getPpcY() / 0.393701f;
	}

	@Override
	public float getPpcX() {
		return ((Lwjgl3Monitor) getMonitor()).getPpcX();
	}

	@Override
	public float getPpcY() {
		return ((Lwjgl3Monitor) getMonitor()).getPpcY();
	}

	@Override
	public float getDensity() {
		return getPpiX() / 160f;
	}

	@Override
	public boolean supportsDisplayModeChange() {
		return true;
	}

	@Override
	public Monitor getPrimaryMonitor() {
		return primaryMonitor;
	}

	@Override
	public Monitor getMonitor() {
		Monitor[] monitors = getMonitors();
		Monitor result = monitors[0];

		int windowX = currentWindow.positionX;
		int windowY = currentWindow.positionY;
		int windowWidth = currentWindow.logicalWidth;
		int windowHeight = currentWindow.logicalHeight;
		int overlap;
		int bestOverlap = 0;

		for (Monitor monitor : monitors) {
			DisplayMode mode = getDisplayMode(monitor);

			overlap = Math.max(0,
					Math.min(windowX + windowWidth, monitor.virtualX + mode.width)
							- Math.max(windowX, monitor.virtualX))
					* Math.max(0, Math.min(windowY + windowHeight, monitor.virtualY + mode.height)
					- Math.max(windowY, monitor.virtualY));

			if (bestOverlap < overlap) {
				bestOverlap = overlap;
				result = monitor;
			}
		}
		return result;
	}

	@Override
	public Monitor[] getMonitors() {
		return monitors;
	}

	@Override
	public DisplayMode[] getDisplayModes() {
		return primaryMonitor.getDisplayModes();
	}

	@Override
	public DisplayMode[] getDisplayModes(Monitor monitor) {
		return ((Lwjgl3Monitor) monitor).getDisplayModes();
	}

	@Override
	public DisplayMode getDisplayMode() {
		return primaryMonitor.getDisplayMode();
	}

	@Override
	public DisplayMode getDisplayMode(Monitor monitor) {
		return ((Lwjgl3Monitor) monitor).getDisplayMode();
	}

	@Override
	public boolean setFullscreenMode(DisplayMode displayMode) {
		return currentWindow.setFullscreenMode((Lwjgl3DisplayMode) displayMode);
	}

	@Override
	public boolean setWindowedMode(int width, int height) {
		return currentWindow.setWindowedMode(width, height);
	}

	@Override
	public void setTitle(String title) {
		currentWindow.setTitle(title != null ? title : "");
	}

	@Override
	public void setUndecorated(boolean undecorated) {
		currentWindow.setUndecorated(undecorated);
	}

	@Override
	public void setResizable(boolean resizable) {
		currentWindow.setResizable(resizable);
	}

	@Override
	public void setVSync(boolean vsync) {
		glfwSwapInterval(vsync ? 1 : 0);
	}

	@Override
	public BufferFormat getBufferFormat() {
		return currentWindow.bufferFormat;
	}

	@Override
	public boolean supportsExtension(String extension) {
		return glfwExtensionSupported(extension);
	}

	@Override
	public void setContinuousRendering(boolean isContinuous) {
		currentWindow.continuous = isContinuous;
	}

	@Override
	public boolean isContinuousRendering() {
		return currentWindow.continuous;
	}

	@Override
	public void requestRendering() {
		currentWindow.requestRendering();
	}

	@Override
	public boolean isFullscreen() {
		return currentWindow.isFullscreen();
	}

	@Override
	public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
		return new Lwjgl3Cursor(currentWindow, pixmap, xHotspot, yHotspot);
	}

	@Override
	public void setCursor(Cursor cursor) {
		((Lwjgl3Cursor) cursor).setCursor();
	}

	@Override
	public void setSystemCursor(Cursor.SystemCursor systemCursor) {
		Lwjgl3Cursor.setSystemCursor(currentWindow, systemCursor);
	}

	@Override
	public void dispose() {

	}

	private static GLVersion buildGLVersion() {
		String versionString = glGetString(GL_VERSION);
		String vendorString = glGetString(GL_VENDOR);
		String rendererString = glGetString(GL_RENDERER);
		return new GLVersion(Application.ApplicationType.Desktop, versionString, vendorString, rendererString);
	}

	static void enumerateMonitorsAndDisplayModes() {

		long monitor = glfwGetPrimaryMonitor();
		primaryMonitor = toLwjgl3Monitor(monitor);

		GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
		primaryMonitorDisplayMode = new Lwjgl3DisplayMode(primaryMonitor, mode.width(), mode.height(),
				mode.refreshRate(), mode.redBits() + mode.greenBits() + mode.blueBits());

		PointerBuffer glfwMonitors = GLFW.glfwGetMonitors();
		monitors = new Lwjgl3Monitor[glfwMonitors.limit()];
		for (int i = 0; i < glfwMonitors.limit(); i++) {
			long handle = glfwMonitors.get(i);
			if (handle == primaryMonitor.getMonitorHandle()) {
				monitors[i] = primaryMonitor;
			} else {
				monitors[i] = toLwjgl3Monitor(glfwMonitors.get(i));
			}
		}
	}

	static Lwjgl3Monitor toLwjgl3Monitor(long glfwMonitor) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer x = stack.mallocInt(1);
			IntBuffer y = stack.mallocInt(1);
			glfwGetMonitorPos(glfwMonitor, x, y);
			int virtualX = x.get(0);
			int virtualY = y.get(0);
			String name = glfwGetMonitorName(glfwMonitor);
			return new Lwjgl3Monitor(glfwMonitor, virtualX, virtualY, name);
		}
	}

}
