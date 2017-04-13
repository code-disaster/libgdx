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

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntMap;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;

/**
 * {@link Cursor} implementation using GLFW functions.
 * <p>
 * Contrary to previous implementations, no additional bookkeeping is done. Remaining cursors are destroyed on
 * {@link org.lwjgl.glfw.GLFW#glfwTerminate}, but to avoid leaking memory, the user application should ensure
 * proper resource cleanup.
 * <p>
 * All available system cursors are created at startup and shared between windows.
 */
public class Lwjgl3Cursor implements Cursor {

	private final Lwjgl3Window window;
	private volatile long handle = 0L;

	private static final IntMap<Long> systemCursors = new IntMap<>();

	/**
	 * This function posts a {@link Runnable} to the main thread, which means it doesn't take effect immediately.
	 * Still, applications are safe to follow up with a call to {@link Graphics#setCursor(Cursor)}, as this is done
	 * through the same mechanism, which ensures the correct call order.
	 */
	Lwjgl3Cursor(Lwjgl3Window window, Pixmap pixmap, int xHotspot, int yHotspot) {
		this.window = window;
		final Pixmap cursor = copyPixmap(pixmap);
		window.postMainThreadRunnable(() -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				GLFWImage image = GLFWImage.callocStack(stack);
				image.width(cursor.getWidth());
				image.height(cursor.getHeight());
				image.pixels(cursor.getPixels());
				glfwMakeContextCurrent(window.getWindowHandle());
				this.handle = glfwCreateCursor(image, xHotspot, yHotspot);
				cursor.dispose();
			}
		});
	}

	@Override
	public void dispose() {
		if (handle != 0L) {
			window.postMainThreadRunnable(() -> {
				glfwDestroyCursor(handle);
				handle = 0L;
			});
		}
	}

	void setCursor() {
		window.postMainThreadRunnable(() -> glfwSetCursor(window.getWindowHandle(), handle));
	}

	/**
	 * Creates a copy of the source pixmap. This enforces RGBA8888 format and a power-of-two size, and
	 * ensures that the copy is available for deferred calls on the main thread, even when the application
	 * decides to dispose the source pixmap right after the call to {@link #Lwjgl3Cursor}.
	 */
	private static Pixmap copyPixmap(Pixmap pixmap) {
		int widht = MathUtils.nextPowerOfTwo(pixmap.getWidth());
		int height = MathUtils.nextPowerOfTwo(pixmap.getHeight());
		Pixmap cursor = new Pixmap(widht, height, Pixmap.Format.RGBA8888);
		cursor.drawPixmap(pixmap, 0, 0);
		return cursor;
	}

	static void setSystemCursor(Lwjgl3Window window, SystemCursor cursor) {
		window.postMainThreadRunnable(() -> glfwSetCursor(
				window.getWindowHandle(), systemCursors.get(cursor.ordinal())));
	}

	static void createSystemCursors() {
		for (SystemCursor cursor : SystemCursor.values()) {
			int shape;
			switch (cursor) {
				case Arrow:
					shape = GLFW_ARROW_CURSOR;
					break;
				case Ibeam:
					shape = GLFW_IBEAM_CURSOR;
					break;
				case Crosshair:
					shape = GLFW_CROSSHAIR_CURSOR;
					break;
				case Hand:
					shape = GLFW_HAND_CURSOR;
					break;
				case HorizontalResize:
					shape = GLFW_HRESIZE_CURSOR;
					break;
				case VerticalResize:
					shape = GLFW_VRESIZE_CURSOR;
					break;
				default:
					throw new GdxRuntimeException("System cursor not implemented: " + cursor.name());
			}
			long handle = glfwCreateStandardCursor(shape);
			systemCursors.put(cursor.ordinal(), handle);
		}
	}

	static void disposeSystemCursors() {
		for (long systemCursor : systemCursors.values()) {
			glfwDestroyCursor(systemCursor);
		}
		systemCursors.clear();
	}

}
