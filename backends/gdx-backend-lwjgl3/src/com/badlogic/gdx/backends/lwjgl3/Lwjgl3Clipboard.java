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
import org.lwjgl.glfw.GLFW;

import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables.__call_main;
import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables.__post_main;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

/**
 * Implementation of {@link Clipboard} that uses the respective GLFW functions.
 * <p>
 * This implementation of {@link Clipboard#getContents()} blocks the render thread. The
 * {@link Clipboard#setContents(String)} function returns immediately, but the actual change
 * is deferred until the next update loop of the main thread.
 */
class Lwjgl3Clipboard implements Clipboard {

	private final Lwjgl3Window window;

	Lwjgl3Clipboard(Lwjgl3Window window) {
		this.window = window;
	}

	@Override
	public String getContents() {
		return __call_main(window, "", GLFW::glfwGetClipboardString);
	}

	@Override
	public void setContents(String content) {
		__post_main(window, context -> glfwSetClipboardString(context, content));
	}

}
