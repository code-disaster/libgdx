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

import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static com.badlogic.gdx.Graphics.Monitor;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorPhysicalSize;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetVideoModes;

public class Lwjgl3Monitor extends Monitor {

	private final long monitor;
	private final Lwjgl3DisplayMode[] modes;
	private Lwjgl3DisplayMode currentMode;
	private final int physicalWidth, physicalHeight;

	Lwjgl3Monitor(long monitor, int virtualX, int virtualY, String name) {
		super(virtualX, virtualY, name);
		this.monitor = monitor;

		GLFWVidMode.Buffer glfwModes = glfwGetVideoModes(monitor);
		modes = new Lwjgl3DisplayMode[glfwModes.limit()];

		for (int i = 0; i < modes.length; i++) {
			GLFWVidMode glfwMode = glfwModes.get(i);
			modes[i] = new Lwjgl3DisplayMode(this, glfwMode.width(), glfwMode.height(), glfwMode.refreshRate(),
					glfwMode.redBits() + glfwMode.greenBits() + glfwMode.blueBits());
		}

		GLFWVidMode glfwMode = glfwGetVideoMode(monitor);
		currentMode = new Lwjgl3DisplayMode(this, glfwMode.width(), glfwMode.height(), glfwMode.refreshRate(),
				glfwMode.redBits() + glfwMode.greenBits() + glfwMode.blueBits());

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer x = stack.mallocInt(1);
			IntBuffer y = stack.mallocInt(1);
			glfwGetMonitorPhysicalSize(monitor, x, y);
			physicalWidth = x.get(0);
			physicalHeight = y.get(0);
		}
	}

	public long getMonitorHandle() {
		return monitor;
	}

	Lwjgl3DisplayMode getDisplayMode() {
		return currentMode;
	}

	Lwjgl3DisplayMode[] getDisplayModes() {
		return modes;
	}

	float getPpcX() {
		Lwjgl3DisplayMode mode = getDisplayMode();
		return mode.width / (float) physicalWidth * 10;
	}

	float getPpcY() {
		Lwjgl3DisplayMode mode = getDisplayMode();
		return mode.height / (float) physicalHeight * 10;
	}

}
