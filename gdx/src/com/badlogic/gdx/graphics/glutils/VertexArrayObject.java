package com.badlogic.gdx.graphics.glutils;

import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/** <p>
 * Convenience class for working with OpenGL vertex array objects, which are required to use with
 * more recent versions of OpenGL on some platforms.
 * </p>
 *
 * <p>
 * The most naive/simple usage is to create and bind one VAO after GL initialisation, and leave it
 * as is until the application shuts down.
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
