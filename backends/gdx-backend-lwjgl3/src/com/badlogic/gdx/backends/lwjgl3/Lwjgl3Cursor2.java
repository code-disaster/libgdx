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

import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntMap;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;

public class Lwjgl3Cursor2 implements Cursor {

	private final Lwjgl3Window2 window;
	private volatile long handle = 0L;

	private static final IntMap<Long> systemCursors = new IntMap<>();

	Lwjgl3Cursor2(Lwjgl3Window2 window, Pixmap pixmap, int xHotspot, int yHotspot) {
		this.window = window;
		validate(pixmap, xHotspot, yHotspot);
		window.postMainThreadRunnable(() -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				GLFWImage image = GLFWImage.callocStack(stack);
				image.width(pixmap.getWidth());
				image.height(pixmap.getHeight());
				image.pixels(pixmap.getPixels());
				handle = glfwCreateCursor(image, xHotspot, yHotspot);
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

	private void validate(Pixmap pixmap, int xHotspot, int yHotspot) {
		if (pixmap.getFormat() != Pixmap.Format.RGBA8888) {
			throw new GdxRuntimeException("Cursor image is not in RGBA8888 format.");
		}

		if (!MathUtils.isPowerOfTwo(pixmap.getWidth())) {
			throw new GdxRuntimeException(
					"Cursor image width of " + pixmap.getWidth() + " is not a power-of-two.");
		}

		if (!MathUtils.isPowerOfTwo(pixmap.getHeight())) {
			throw new GdxRuntimeException(
					"Cursor image height of " + pixmap.getHeight() + " is not a power-of-two.");
		}

		if (xHotspot < 0 || xHotspot >= pixmap.getWidth()) {
			throw new GdxRuntimeException("xHotspot coordinate of " + xHotspot
					+ " is not within image width bounds: [0, " + pixmap.getWidth() + ").");
		}

		if (yHotspot < 0 || yHotspot >= pixmap.getHeight()) {
			throw new GdxRuntimeException("yHotspot coordinate of " + yHotspot
					+ " is not within image height bounds: [0, " + pixmap.getHeight() + ").");
		}
	}

	static void setSystemCursor(Lwjgl3Window2 window, SystemCursor cursor) {
		glfwSetCursor(window.getWindowHandle(), systemCursors.get(cursor.ordinal()));
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
