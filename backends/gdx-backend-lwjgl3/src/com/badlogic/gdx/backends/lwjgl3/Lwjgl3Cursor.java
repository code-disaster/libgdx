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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntMap;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * {@link Cursor} implementation using GLFW functions.
 * <p>
 * The {@link GLFW#glfwCreateCursor} function must be called on the main thread, and requires the window GL context to
 * be current. To meet these requirements, this implementation does:
 * <p>
 * <ul>
 * <li>post a runnable to itself, so that the owner's window GL context is not current anymore</li>
 * <li>then delegate a call to the main thread, and wait (block) for completion</li>
 * <li>in the main thread, make the GL context current, then finally create the cursor</li>
 * </ul>
 * <p>
 * To set and dispose a cursor, the same staggered (but non-blocking) mechanism is used, to ensure that execution of
 * API calls is done in the correct order.
 * <p>
 * Contrary to the previous implementation, no additional bookkeeping is done. Remaining cursors are destroyed on
 * {@link GLFW#glfwTerminate} at application exit, but to avoid leaking memory, the user application should perform
 * proper cleanup of resources.
 * <p>
 * {@link SystemCursor} resources are created at application start, and shared between windows.
 */
public class Lwjgl3Cursor implements Cursor {

	private final Lwjgl3Window window;
	private volatile long handle = 0L;

	private static final IntMap<Long> systemCursors = new IntMap<>();

	/**
	 * Creation of the cursor is deferred, which means it doesn't take effect immediately. Still, applications are
	 * safe to follow up with a call to {@link Graphics#setCursor(Cursor)}.
	 * <p>
	 * The pixel data is copied, so the caller is safe to dispose the {@link Pixmap} right after creating the cursor
	 * instance.
	 */
	Lwjgl3Cursor(Lwjgl3Window window, Pixmap pixmap, int xHotspot, int yHotspot) {
		this.window = window;
		final Pixmap cursorPixmap = copyPixmap(pixmap);
		__post_render(() -> {
			this.handle = __call_main(window, 0L, context -> {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					GLFWImage image = GLFWImage.callocStack(stack);
					image.width(cursorPixmap.getWidth());
					image.height(cursorPixmap.getHeight());
					image.pixels(cursorPixmap.getPixels());
					__context_main(context);
					long cursor = glfwCreateCursor(image, xHotspot, yHotspot);
					cursorPixmap.dispose();
					return cursor;
				}
			});
			if (handle == 0L) {
				Gdx.app.log("Lwjgl3Application", "Failed to create cursor");
			}
		});
	}

	@Override
	public void dispose() {
		__post_render(() -> {
			if (handle != 0L) {
				handle = __call_main(window, 0L, context -> {
					glfwDestroyCursor(handle);
					return 0L;
				});
			}
		});
	}

	void setCursor() {
		__post_render(() ->
				__post_main(window, context -> glfwSetCursor(context, handle))
		);
	}

	private static Pixmap copyPixmap(Pixmap pixmap) {
		int width = MathUtils.nextPowerOfTwo(pixmap.getWidth());
		int height = MathUtils.nextPowerOfTwo(pixmap.getHeight());
		Pixmap cursor = new Pixmap(width, height, Pixmap.Format.RGBA8888);
		cursor.drawPixmap(pixmap, 0, 0);
		return cursor;
	}

	static void setSystemCursor(Lwjgl3Window window, SystemCursor cursor) {
		__post_render(() ->
				__post_main(window, context -> {
					long handle = cursor != null ? systemCursors.get(cursor.ordinal()) : NULL;
					glfwSetCursor(context, handle);
				})
		);
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
