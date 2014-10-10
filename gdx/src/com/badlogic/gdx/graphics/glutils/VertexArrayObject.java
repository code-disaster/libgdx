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

package com.badlogic.gdx.graphics.glutils;

import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/** <p>
 * Convenience class for working with OpenGL 3.0+ vertex array objects.
 * </p>
 * 
 * <p>
 * The most naive/simple usage is to create and bind one VAO after GL initialisation, and leave it as is until the application
 * shuts down.
 * </p>
 * 
 * <p>
 * VertexArrayObjects must be disposed via the {@link #dispose()} method when no longer needed
 * </p>
 * 
 * @author dludwig */
public class VertexArrayObject implements Disposable {
	final static IntBuffer tmpHandle = BufferUtils.newIntBuffer(1);

	final int vaoHandle;

	public VertexArrayObject () {
		tmpHandle.clear();
		Gdx.gl30.glGenVertexArrays(1, tmpHandle);
		vaoHandle = tmpHandle.get();
	}

	@Override
	public void dispose () {
		if (vaoHandle != 0) {
			tmpHandle.clear();
			tmpHandle.put(0, vaoHandle);
			Gdx.gl30.glDeleteVertexArrays(1, tmpHandle);
		}
	}

	public void bind () {
		if (vaoHandle != 0) {
			Gdx.gl30.glBindVertexArray(vaoHandle);
		}
	}

	public static void unbind () {
		Gdx.gl30.glBindVertexArray(0);
	}
}
