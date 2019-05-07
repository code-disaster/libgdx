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
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.TimeUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.*;

import java.util.Arrays;

import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables.__post_main;
import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables.__post_render;
import static org.lwjgl.glfw.GLFW.*;

public class Lwjgl3Input implements Input, Disposable {

	private final Lwjgl3Window window;
	private final InputEventQueue eventQueue = new InputEventQueue();
	private InputProcessor inputProcessor;

	private int mouseX, mouseY;
	private int mousePressed;
	private long pressedButtons;
	private int deltaX, deltaY;
	private boolean justTouched;
	private int keysPressed;
	private long[] pressedKeys = new long[4];
	private boolean keyJustPressed;
	private long[] justPressedKeys = new long[4];
	private char lastCharacter;
	private boolean catched = false;

	private final GLFWKeyCallback keyCallback = new GLFWKeyCallback() {
		@Override
		public void invoke(long handle, int key, int scancode, int action, int mods) {
			final int gdxKey = toGdxKeyCode(key);
			__post_render(handle, () -> {
				switch (action) {
					case GLFW_PRESS:
						keysPressed++;
						pressedKeys[gdxKey / 64] |= (1L << (gdxKey % 64));
						keyJustPressed = true;
						justPressedKeys[gdxKey / 64] |= (1L << (gdxKey % 64));
						lastCharacter = 0;
						window.requestRendering();
						eventQueue.keyDown(gdxKey);
						break;
					case GLFW.GLFW_RELEASE:
						keysPressed--;
						pressedKeys[gdxKey / 64] &= ~(1L << (gdxKey % 64));
						window.requestRendering();
						eventQueue.keyUp(gdxKey);
						break;
					case GLFW.GLFW_REPEAT:
						if (lastCharacter != 0) {
							window.requestRendering();
							eventQueue.keyTyped(lastCharacter);
						}
						break;
				}
			});

			if (action == GLFW_PRESS) {
				char character = characterForKeyCode(gdxKey);
				if (character != 0) {
					charCallback.invoke(handle, character);
				}
			}
		}
	};

	private final GLFWCharCallback charCallback = new GLFWCharCallback() {
		@Override
		public void invoke(long handle, int codepoint) {
			if ((codepoint & 0xff00) == 0xf700) {
				return;
			}
			__post_render(handle, () -> {
				lastCharacter = (char) codepoint;
				window.requestRendering();
				eventQueue.keyTyped(lastCharacter);
			});
		}
	};

	private final GLFWScrollCallback scrollCallback = new GLFWScrollCallback() {
		private final long pauseTime = 250000000L; //250ms
		private float scrollYRemainder;
		private long lastScrollEventTime;

		@Override
		public void invoke(long handle, double scrollX, double scrollY) {
			__post_render(handle, () -> {
				window.requestRendering();
				if (scrollYRemainder > 0 && scrollY < 0 || scrollYRemainder < 0 && scrollY > 0 ||
						TimeUtils.nanoTime() - lastScrollEventTime > pauseTime) {
					// fire a scroll event immediately:
					//  - if the scroll direction changes;
					//  - if the user did not move the wheel for more than 250ms
					scrollYRemainder = 0;
					int scrollAmount = (int) -Math.signum(scrollY);
					eventQueue.scrolled(scrollAmount);
					lastScrollEventTime = TimeUtils.nanoTime();
				} else {
					scrollYRemainder += scrollY;
					while (Math.abs(scrollYRemainder) >= 1) {
						int scrollAmount = (int) -Math.signum(scrollY);
						eventQueue.scrolled(scrollAmount);
						lastScrollEventTime = TimeUtils.nanoTime();
						scrollYRemainder += scrollAmount;
					}
				}
			});
		}
	};

	private final GLFWCursorPosCallback cursorPosCallback = new GLFWCursorPosCallback() {

		private int logicalMouseY;
		private int logicalMouseX;

		@Override
		public void invoke(long handle, double x, double y) {
			boolean doScale = window.getConfig().hdpiMode == HdpiMode.Pixels;
			float xScale = doScale ? window.backBufferWidth / (float) window.logicalWidth : 1.0f;
			float yScale = doScale ? window.backBufferHeight / (float) window.logicalHeight : 1.0f;
			final int deltaX = (int) ((x - logicalMouseX) * xScale);
			final int deltaY = (int) ((y - logicalMouseY) * yScale);
			final int mouseX = logicalMouseX = (int) (x * xScale);
			final int mouseY = logicalMouseY = (int) (y * yScale);
			__post_render(handle, () -> {
				Lwjgl3Input.this.deltaX = deltaX;
				Lwjgl3Input.this.deltaY = deltaY;
				Lwjgl3Input.this.mouseX = mouseX;
				Lwjgl3Input.this.mouseY = mouseY;
				window.requestRendering();
				if (mousePressed > 0) {
					eventQueue.touchDragged(mouseX, mouseY, 0);
				} else {
					eventQueue.mouseMoved(mouseX, mouseY);
				}
			});
		}
	};

	private final GLFWCursorEnterCallback cursorEnterCallback = new GLFWCursorEnterCallback() {
		@Override
		public void invoke(long handle, boolean entered) {
			__post_render(handle, () -> window.getWindowListener().cursorEntered(entered));
		}
	};

	private final GLFWMouseButtonCallback mouseButtonCallback = new GLFWMouseButtonCallback() {
		@Override
		public void invoke(long handle, int button, int action, int mods) {
			final int gdxButton = toGdxButton(button);
			if (button != -1 && gdxButton == -1) {
				return;
			}
			__post_render(handle, () -> {
				if (action == GLFW_PRESS) {
					mousePressed++;
					pressedButtons |= (1L << gdxButton);
					justTouched = true;
					window.requestRendering();
					eventQueue.touchDown(mouseX, mouseY, 0, gdxButton);
				} else {
					mousePressed = Math.max(0, mousePressed - 1);
					pressedButtons &= ~(1L << gdxButton);
					window.requestRendering();
					eventQueue.touchUp(mouseX, mouseY, 0, gdxButton);
				}
			});
		}

		private int toGdxButton(int button) {
			switch (button) {
				case 0:
					return Buttons.LEFT;
				case 1:
					return Buttons.RIGHT;
				case 2:
					return Buttons.MIDDLE;
				case 3:
					return Buttons.BACK;
				case 4:
					return Buttons.FORWARD;
				default:
					return -1;
			}
		}
	};

	Lwjgl3Input(Lwjgl3Window window) {
		this.window = window;
	}

	void setupCallbacks(long handle) {
		reset();
		glfwSetKeyCallback(handle, keyCallback);
		glfwSetCharCallback(handle, charCallback);
		glfwSetScrollCallback(handle, scrollCallback);
		glfwSetCursorPosCallback(handle, cursorPosCallback);
		glfwSetCursorEnterCallback(handle, cursorEnterCallback);
		glfwSetMouseButtonCallback(handle, mouseButtonCallback);
	}

	void update() {
		eventQueue.setProcessor(inputProcessor);
		eventQueue.drain();
	}

	void prepareNext() {
		justTouched = false;
		if (keyJustPressed) {
			keyJustPressed = false;
			Arrays.fill(justPressedKeys, 0L);
		}
		deltaX = 0;
		deltaY = 0;
	}

	void reset() {
		justTouched = false;
		pressedButtons = 0L;
		keyJustPressed = false;
		Arrays.fill(justPressedKeys, 0L);
		eventQueue.setProcessor(null);
		eventQueue.drain();
	}

	@Override
	public float getAccelerometerX() {
		return 0;
	}

	@Override
	public float getAccelerometerY() {
		return 0;
	}

	@Override
	public float getAccelerometerZ() {
		return 0;
	}

	@Override
	public float getGyroscopeX() {
		return 0;
	}

	@Override
	public float getGyroscopeY() {
		return 0;
	}

	@Override
	public float getGyroscopeZ() {
		return 0;
	}

	@Override
	public int getMaxPointers() {
		return 1;
	}

	@Override
	public int getX() {
		return mouseX;
	}

	@Override
	public int getX(int pointer) {
		return pointer == 0 ? mouseX : 0;
	}

	@Override
	public int getDeltaX() {
		return deltaX;
	}

	@Override
	public int getDeltaX(int pointer) {
		return pointer == 0 ? deltaX : 0;
	}

	@Override
	public int getY() {
		return mouseY;
	}

	@Override
	public int getY(int pointer) {
		return pointer == 0 ? mouseY : 0;
	}

	@Override
	public int getDeltaY() {
		return deltaY;
	}

	@Override
	public int getDeltaY(int pointer) {
		return pointer == 0 ? deltaY : 0;
	}

	@Override
	public boolean isTouched() {
		return pressedButtons != 0L;
	}

	@Override
	public boolean justTouched() {
		return justTouched;
	}

	@Override
	public boolean isTouched(int pointer) {
		return pointer == 0 && isTouched();
	}

	@Override
	public float getPressure() {
		return getPressure(0);
	}

	@Override
	public float getPressure(int pointer) {
		return isTouched(pointer) ? 1 : 0;
	}

	@Override
	public boolean isButtonPressed(int button) {
		return (pressedButtons & (1L << button)) != 0;
	}

	@Override
	public boolean isButtonJustPressed(int button) {
		throw new IllegalStateException();
	}

	@Override
	public boolean isKeyPressed(int key) {
		if (key == Keys.ANY_KEY) {
			return keysPressed > 0;
		}
		long bit = 1L << (key % 64);
		return (pressedKeys[key / 64] & bit) != 0;
	}

	@Override
	public boolean isKeyJustPressed(int key) {
		if (key == Keys.ANY_KEY) {
			return keyJustPressed;
		}
		if (key < 0 || key > 256) {
			return false;
		}
		long bit = 1L << (key % 64);
		return (justPressedKeys[key / 64] & bit) != 0;
	}

	@Override
	public void getTextInput(TextInputListener listener, String title, String text, String hint) {
		// FIXME getTextInput does nothing
		listener.canceled();
	}

	@Override
	public void setOnscreenKeyboardVisible(boolean visible) {

	}

	@Override
	public void vibrate(int milliseconds) {

	}

	@Override
	public void vibrate(long[] pattern, int repeat) {

	}

	@Override
	public void cancelVibrate() {

	}

	@Override
	public float getAzimuth() {
		return 0;
	}

	@Override
	public float getPitch() {
		return 0;
	}

	@Override
	public float getRoll() {
		return 0;
	}

	@Override
	public void getRotationMatrix(float[] matrix) {

	}

	@Override
	public long getCurrentEventTime() {
		return eventQueue.getCurrentEventTime();
	}

	@Override
	public void setCatchBackKey(boolean catchBack) {

	}

	@Override
	public boolean isCatchBackKey() {
		return false;
	}

	@Override
	public void setCatchMenuKey(boolean catchMenu) {

	}

	@Override
	public boolean isCatchMenuKey() {
		return false;
	}

	@Override
	public void setInputProcessor(InputProcessor processor) {
		this.inputProcessor = processor;
	}

	@Override
	public InputProcessor getInputProcessor() {
		return inputProcessor;
	}

	@Override
	public boolean isPeripheralAvailable(Peripheral peripheral) {
		return peripheral == Peripheral.HardwareKeyboard;
	}

	@Override
	public int getRotation() {
		return 0;
	}

	@Override
	public Orientation getNativeOrientation() {
		return Orientation.Landscape;
	}

	@Override
	public void setCursorCatched(boolean catched) {
		this.catched = catched;
		__post_main(window, context -> glfwSetInputMode(context, GLFW_CURSOR,
				catched ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL));
	}

	@Override
	public boolean isCursorCatched() {
		return catched;
	}

	@Override
	public void setCursorPosition(int x, int y) {
		boolean doScale = window.getConfig().hdpiMode == HdpiMode.Pixels;
		float xScale = doScale ? window.logicalWidth / (float) window.backBufferWidth : 1.0f;
		float yScale = doScale ? window.logicalHeight / (float) window.backBufferHeight : 1.0f;
		final int posX = (int) (x * xScale);
		final int posY = (int) (y * yScale);
		__post_main(window, context -> glfwSetCursorPos(context, posX, posY));
	}

	void disposeCallbacks(long handle) {
		glfwSetKeyCallback(handle, null);
		glfwSetCharCallback(handle, null);
		glfwSetScrollCallback(handle, null);
		glfwSetCursorPosCallback(handle, null);
		glfwSetCursorEnterCallback(handle, null);
		glfwSetMouseButtonCallback(handle, null);
	}

	@Override
	public void dispose() {
		keyCallback.free();
		charCallback.free();
		scrollCallback.free();
		cursorPosCallback.free();
		cursorEnterCallback.free();
		mouseButtonCallback.free();
	}

	private static char characterForKeyCode(int key) {
		// Map certain key codes to character codes.
		switch (key) {
			case Keys.BACKSPACE:
				return 8;
			case Keys.TAB:
				return '\t';
			case Keys.FORWARD_DEL:
				return 127;
			case Keys.ENTER:
				return '\n';
		}
		return 0;
	}

	public static int toGdxKeyCode(int lwjglKeyCode) {
		switch (lwjglKeyCode) {
			case GLFW_KEY_SPACE:
				return Keys.SPACE;
			case GLFW_KEY_APOSTROPHE:
				return Keys.APOSTROPHE;
			case GLFW_KEY_COMMA:
				return Keys.COMMA;
			case GLFW_KEY_MINUS:
				return Keys.MINUS;
			case GLFW_KEY_PERIOD:
				return Keys.PERIOD;
			case GLFW_KEY_SLASH:
				return Keys.SLASH;
			case GLFW_KEY_0:
				return Keys.NUM_0;
			case GLFW_KEY_1:
				return Keys.NUM_1;
			case GLFW_KEY_2:
				return Keys.NUM_2;
			case GLFW_KEY_3:
				return Keys.NUM_3;
			case GLFW_KEY_4:
				return Keys.NUM_4;
			case GLFW_KEY_5:
				return Keys.NUM_5;
			case GLFW_KEY_6:
				return Keys.NUM_6;
			case GLFW_KEY_7:
				return Keys.NUM_7;
			case GLFW_KEY_8:
				return Keys.NUM_8;
			case GLFW_KEY_9:
				return Keys.NUM_9;
			case GLFW_KEY_SEMICOLON:
				return Keys.SEMICOLON;
			case GLFW_KEY_EQUAL:
				return Keys.EQUALS;
			case GLFW_KEY_A:
				return Keys.A;
			case GLFW_KEY_B:
				return Keys.B;
			case GLFW_KEY_C:
				return Keys.C;
			case GLFW_KEY_D:
				return Keys.D;
			case GLFW_KEY_E:
				return Keys.E;
			case GLFW_KEY_F:
				return Keys.F;
			case GLFW_KEY_G:
				return Keys.G;
			case GLFW_KEY_H:
				return Keys.H;
			case GLFW_KEY_I:
				return Keys.I;
			case GLFW_KEY_J:
				return Keys.J;
			case GLFW_KEY_K:
				return Keys.K;
			case GLFW_KEY_L:
				return Keys.L;
			case GLFW_KEY_M:
				return Keys.M;
			case GLFW_KEY_N:
				return Keys.N;
			case GLFW_KEY_O:
				return Keys.O;
			case GLFW_KEY_P:
				return Keys.P;
			case GLFW_KEY_Q:
				return Keys.Q;
			case GLFW_KEY_R:
				return Keys.R;
			case GLFW_KEY_S:
				return Keys.S;
			case GLFW_KEY_T:
				return Keys.T;
			case GLFW_KEY_U:
				return Keys.U;
			case GLFW_KEY_V:
				return Keys.V;
			case GLFW_KEY_W:
				return Keys.W;
			case GLFW_KEY_X:
				return Keys.X;
			case GLFW_KEY_Y:
				return Keys.Y;
			case GLFW_KEY_Z:
				return Keys.Z;
			case GLFW_KEY_LEFT_BRACKET:
				return Keys.LEFT_BRACKET;
			case GLFW_KEY_BACKSLASH:
				return Keys.BACKSLASH;
			case GLFW_KEY_RIGHT_BRACKET:
				return Keys.RIGHT_BRACKET;
			case GLFW_KEY_GRAVE_ACCENT:
				return Keys.GRAVE;
			case GLFW_KEY_WORLD_1:
			case GLFW_KEY_WORLD_2:
				return Keys.UNKNOWN;
			case GLFW_KEY_ESCAPE:
				return Keys.ESCAPE;
			case GLFW_KEY_ENTER:
				return Keys.ENTER;
			case GLFW_KEY_TAB:
				return Keys.TAB;
			case GLFW_KEY_BACKSPACE:
				return Keys.BACKSPACE;
			case GLFW_KEY_INSERT:
				return Keys.INSERT;
			case GLFW_KEY_DELETE:
				return Keys.FORWARD_DEL;
			case GLFW_KEY_RIGHT:
				return Keys.RIGHT;
			case GLFW_KEY_LEFT:
				return Keys.LEFT;
			case GLFW_KEY_DOWN:
				return Keys.DOWN;
			case GLFW_KEY_UP:
				return Keys.UP;
			case GLFW_KEY_PAGE_UP:
				return Keys.PAGE_UP;
			case GLFW_KEY_PAGE_DOWN:
				return Keys.PAGE_DOWN;
			case GLFW_KEY_HOME:
				return Keys.HOME;
			case GLFW_KEY_END:
				return Keys.END;
			case GLFW_KEY_CAPS_LOCK:
			case GLFW_KEY_SCROLL_LOCK:
			case GLFW_KEY_NUM_LOCK:
			case GLFW_KEY_PRINT_SCREEN:
			case GLFW_KEY_PAUSE:
				return Keys.UNKNOWN;
			case GLFW_KEY_F1:
				return Keys.F1;
			case GLFW_KEY_F2:
				return Keys.F2;
			case GLFW_KEY_F3:
				return Keys.F3;
			case GLFW_KEY_F4:
				return Keys.F4;
			case GLFW_KEY_F5:
				return Keys.F5;
			case GLFW_KEY_F6:
				return Keys.F6;
			case GLFW_KEY_F7:
				return Keys.F7;
			case GLFW_KEY_F8:
				return Keys.F8;
			case GLFW_KEY_F9:
				return Keys.F9;
			case GLFW_KEY_F10:
				return Keys.F10;
			case GLFW_KEY_F11:
				return Keys.F11;
			case GLFW_KEY_F12:
				return Keys.F12;
			case GLFW_KEY_F13:
			case GLFW_KEY_F14:
			case GLFW_KEY_F15:
			case GLFW_KEY_F16:
			case GLFW_KEY_F17:
			case GLFW_KEY_F18:
			case GLFW_KEY_F19:
			case GLFW_KEY_F20:
			case GLFW_KEY_F21:
			case GLFW_KEY_F22:
			case GLFW_KEY_F23:
			case GLFW_KEY_F24:
			case GLFW_KEY_F25:
				return Keys.UNKNOWN;
			case GLFW_KEY_KP_0:
				return Keys.NUMPAD_0;
			case GLFW_KEY_KP_1:
				return Keys.NUMPAD_1;
			case GLFW_KEY_KP_2:
				return Keys.NUMPAD_2;
			case GLFW_KEY_KP_3:
				return Keys.NUMPAD_3;
			case GLFW_KEY_KP_4:
				return Keys.NUMPAD_4;
			case GLFW_KEY_KP_5:
				return Keys.NUMPAD_5;
			case GLFW_KEY_KP_6:
				return Keys.NUMPAD_6;
			case GLFW_KEY_KP_7:
				return Keys.NUMPAD_7;
			case GLFW_KEY_KP_8:
				return Keys.NUMPAD_8;
			case GLFW_KEY_KP_9:
				return Keys.NUMPAD_9;
			case GLFW_KEY_KP_DECIMAL:
				return Keys.PERIOD;
			case GLFW_KEY_KP_DIVIDE:
				return Keys.SLASH;
			case GLFW_KEY_KP_MULTIPLY:
				return Keys.STAR;
			case GLFW_KEY_KP_SUBTRACT:
				return Keys.MINUS;
			case GLFW_KEY_KP_ADD:
				return Keys.PLUS;
			case GLFW_KEY_KP_ENTER:
				return Keys.ENTER;
			case GLFW_KEY_KP_EQUAL:
				return Keys.EQUALS;
			case GLFW_KEY_LEFT_SHIFT:
				return Keys.SHIFT_LEFT;
			case GLFW_KEY_LEFT_CONTROL:
				return Keys.CONTROL_LEFT;
			case GLFW_KEY_LEFT_ALT:
				return Keys.ALT_LEFT;
			case GLFW_KEY_LEFT_SUPER:
				return Keys.SYM;
			case GLFW_KEY_RIGHT_SHIFT:
				return Keys.SHIFT_RIGHT;
			case GLFW_KEY_RIGHT_CONTROL:
				return Keys.CONTROL_RIGHT;
			case GLFW_KEY_RIGHT_ALT:
				return Keys.ALT_RIGHT;
			case GLFW_KEY_RIGHT_SUPER:
				return Keys.SYM;
			case GLFW_KEY_MENU:
				return Keys.MENU;
			default:
				return Keys.UNKNOWN;
		}
	}

	public static int toGlfwKeyCode(int gdxKeyCode) {
		switch (gdxKeyCode) {
			case Keys.SPACE:
				return GLFW_KEY_SPACE;
			case Keys.APOSTROPHE:
				return GLFW_KEY_APOSTROPHE;
			case Keys.COMMA:
				return GLFW_KEY_COMMA;
			case Keys.PERIOD:
				return GLFW_KEY_PERIOD;
			case Keys.NUM_0:
				return GLFW_KEY_0;
			case Keys.NUM_1:
				return GLFW_KEY_1;
			case Keys.NUM_2:
				return GLFW_KEY_2;
			case Keys.NUM_3:
				return GLFW_KEY_3;
			case Keys.NUM_4:
				return GLFW_KEY_4;
			case Keys.NUM_5:
				return GLFW_KEY_5;
			case Keys.NUM_6:
				return GLFW_KEY_6;
			case Keys.NUM_7:
				return GLFW_KEY_7;
			case Keys.NUM_8:
				return GLFW_KEY_8;
			case Keys.NUM_9:
				return GLFW_KEY_9;
			case Keys.SEMICOLON:
				return GLFW_KEY_SEMICOLON;
			case Keys.EQUALS:
				return GLFW_KEY_EQUAL;
			case Keys.A:
				return GLFW_KEY_A;
			case Keys.B:
				return GLFW_KEY_B;
			case Keys.C:
				return GLFW_KEY_C;
			case Keys.D:
				return GLFW_KEY_D;
			case Keys.E:
				return GLFW_KEY_E;
			case Keys.F:
				return GLFW_KEY_F;
			case Keys.G:
				return GLFW_KEY_G;
			case Keys.H:
				return GLFW_KEY_H;
			case Keys.I:
				return GLFW_KEY_I;
			case Keys.J:
				return GLFW_KEY_J;
			case Keys.K:
				return GLFW_KEY_K;
			case Keys.L:
				return GLFW_KEY_L;
			case Keys.M:
				return GLFW_KEY_M;
			case Keys.N:
				return GLFW_KEY_N;
			case Keys.O:
				return GLFW_KEY_O;
			case Keys.P:
				return GLFW_KEY_P;
			case Keys.Q:
				return GLFW_KEY_Q;
			case Keys.R:
				return GLFW_KEY_R;
			case Keys.S:
				return GLFW_KEY_S;
			case Keys.T:
				return GLFW_KEY_T;
			case Keys.U:
				return GLFW_KEY_U;
			case Keys.V:
				return GLFW_KEY_V;
			case Keys.W:
				return GLFW_KEY_W;
			case Keys.X:
				return GLFW_KEY_X;
			case Keys.Y:
				return GLFW_KEY_Y;
			case Keys.Z:
				return GLFW_KEY_Z;
			case Keys.LEFT_BRACKET:
				return GLFW_KEY_LEFT_BRACKET;
			case Keys.BACKSLASH:
				return GLFW_KEY_BACKSLASH;
			case Keys.RIGHT_BRACKET:
				return GLFW_KEY_RIGHT_BRACKET;
			case Keys.GRAVE:
				return GLFW_KEY_GRAVE_ACCENT;
			case Keys.ESCAPE:
				return GLFW_KEY_ESCAPE;
			case Keys.ENTER:
				return GLFW_KEY_ENTER;
			case Keys.TAB:
				return GLFW_KEY_TAB;
			case Keys.BACKSPACE:
				return GLFW_KEY_BACKSPACE;
			case Keys.INSERT:
				return GLFW_KEY_INSERT;
			case Keys.FORWARD_DEL:
				return GLFW_KEY_DELETE;
			case Keys.RIGHT:
				return GLFW_KEY_RIGHT;
			case Keys.LEFT:
				return GLFW_KEY_LEFT;
			case Keys.DOWN:
				return GLFW_KEY_DOWN;
			case Keys.UP:
				return GLFW_KEY_UP;
			case Keys.PAGE_UP:
				return GLFW_KEY_PAGE_UP;
			case Keys.PAGE_DOWN:
				return GLFW_KEY_PAGE_DOWN;
			case Keys.HOME:
				return GLFW_KEY_HOME;
			case Keys.END:
				return GLFW_KEY_END;
			case Keys.F1:
				return GLFW_KEY_F1;
			case Keys.F2:
				return GLFW_KEY_F2;
			case Keys.F3:
				return GLFW_KEY_F3;
			case Keys.F4:
				return GLFW_KEY_F4;
			case Keys.F5:
				return GLFW_KEY_F5;
			case Keys.F6:
				return GLFW_KEY_F6;
			case Keys.F7:
				return GLFW_KEY_F7;
			case Keys.F8:
				return GLFW_KEY_F8;
			case Keys.F9:
				return GLFW_KEY_F9;
			case Keys.F10:
				return GLFW_KEY_F10;
			case Keys.F11:
				return GLFW_KEY_F11;
			case Keys.F12:
				return GLFW_KEY_F12;
			case Keys.NUMPAD_0:
				return GLFW_KEY_KP_0;
			case Keys.NUMPAD_1:
				return GLFW_KEY_KP_1;
			case Keys.NUMPAD_2:
				return GLFW_KEY_KP_2;
			case Keys.NUMPAD_3:
				return GLFW_KEY_KP_3;
			case Keys.NUMPAD_4:
				return GLFW_KEY_KP_4;
			case Keys.NUMPAD_5:
				return GLFW_KEY_KP_5;
			case Keys.NUMPAD_6:
				return GLFW_KEY_KP_6;
			case Keys.NUMPAD_7:
				return GLFW_KEY_KP_7;
			case Keys.NUMPAD_8:
				return GLFW_KEY_KP_8;
			case Keys.NUMPAD_9:
				return GLFW_KEY_KP_9;
			case Keys.SLASH:
				return GLFW_KEY_KP_DIVIDE;
			case Keys.STAR:
				return GLFW_KEY_KP_MULTIPLY;
			case Keys.MINUS:
				return GLFW_KEY_KP_SUBTRACT;
			case Keys.PLUS:
				return GLFW_KEY_KP_ADD;
			case Keys.SHIFT_LEFT:
				return GLFW_KEY_LEFT_SHIFT;
			case Keys.CONTROL_LEFT:
				return GLFW_KEY_LEFT_CONTROL;
			case Keys.ALT_LEFT:
				return GLFW_KEY_LEFT_ALT;
			case Keys.SYM:
				return GLFW_KEY_LEFT_SUPER;
			case Keys.SHIFT_RIGHT:
				return GLFW_KEY_RIGHT_SHIFT;
			case Keys.CONTROL_RIGHT:
				return GLFW_KEY_RIGHT_CONTROL;
			case Keys.ALT_RIGHT:
				return GLFW_KEY_RIGHT_ALT;
			case Keys.MENU:
				return GLFW_KEY_MENU;
			default:
				return 0;
		}
	}

}
