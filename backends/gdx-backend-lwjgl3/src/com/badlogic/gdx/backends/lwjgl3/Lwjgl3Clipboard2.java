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

import com.badlogic.gdx.utils.Clipboard;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

class Lwjgl3Clipboard2 implements Clipboard {

	private final Lwjgl3Window2 window;

	Lwjgl3Clipboard2(Lwjgl3Window2 window) {
		this.window = window;
	}

	@Override
	public String getContents() {
		try {
			return window.postMainThreadRunnable(() -> glfwGetClipboardString(window.getWindowHandle()));
		} catch (InterruptedException e) {
			return "";
		}
	}

	@Override
	public void setContents(String content) {
		window.postMainThreadRunnable(() -> glfwSetClipboardString(window.getWindowHandle(), content));
	}

}
