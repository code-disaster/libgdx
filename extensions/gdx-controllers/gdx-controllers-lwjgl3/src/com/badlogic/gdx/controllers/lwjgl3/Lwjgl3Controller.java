package com.badlogic.gdx.controllers.lwjgl3;

import com.badlogic.gdx.controllers.*;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables.__post_render;
import static org.lwjgl.glfw.GLFW.*;

public class Lwjgl3Controller implements Controller {

	final Lwjgl3ControllerManager manager;
	final Array<ControllerListener> listeners = new Array<>();
	final int index;
	final float[] axisState;
	final byte[] buttonState;
	final String name;

	public Lwjgl3Controller (Lwjgl3ControllerManager manager, int index) {
		this.manager = manager;
		this.index = index;
		this.axisState = new float[glfwGetJoystickAxes(index).limit()];
		this.buttonState = new byte[glfwGetJoystickButtons(index).limit()];
		this.name = glfwGetJoystickName(index);
	}

	boolean update () {

		FloatBuffer axes = glfwGetJoystickAxes(index);
		if (axes == null) {
			return false;
		}

		ByteBuffer buttons = glfwGetJoystickButtons(index);
		if (buttons == null) {
			return false;
		}

		for (int i = 0; i < axes.limit(); i++) {
			float state = axes.get(i);
			if (!MathUtils.isEqual(state, axisState[i])) {
				axisChanged(i, state);
				axisState[i] = state;
			}
		}

		for (int i = 0; i < buttons.limit(); i++) {
			byte state = buttons.get(i);
			if (state != buttonState[i]) {
				buttonChanged(i, state);
				buttonState[i] = state;
			}
		}

		return true;
	}

	private void axisChanged (int index, float state) {
		__post_render(() -> {
			for (ControllerListener listener : listeners) {
				listener.axisMoved(this, index, state);
			}
			manager.axisChanged(this, index, state);
		});
	}

	private void buttonChanged (int index, byte state) {
		__post_render(() -> {
			boolean pressed = state == GLFW_PRESS;
			for (ControllerListener listener : listeners) {
				if (pressed) {
					listener.buttonDown(this, index);
				} else {
					listener.buttonUp(this, index);
				}
			}
			manager.buttonChanged(this, index, pressed);
		});
	}

	@Override
	public void addListener (ControllerListener listener) {
		listeners.add(listener);
	}

	@Override
	public void removeListener (ControllerListener listener) {
		listeners.removeValue(listener, true);
	}

	@Override
	public boolean getButton (int buttonCode) {
		if (buttonCode < 0 || buttonCode >= buttonState.length) {
			return false;
		}
		return buttonState[buttonCode] == GLFW_PRESS;
	}

	@Override
	public float getAxis (int axisCode) {
		if (axisCode < 0 || axisCode >= axisState.length) {
			return 0;
		}
		return axisState[axisCode];
	}

	@Override
	public PovDirection getPov (int povCode) {
		return PovDirection.center;
	}

	@Override
	public boolean getSliderX (int sliderCode) {
		return false;
	}

	@Override
	public boolean getSliderY (int sliderCode) {
		return false;
	}

	@Override
	public Vector3 getAccelerometer (int accelerometerCode) {
		return Vector3.Zero;
	}

	@Override
	public void setAccelerometerSensitivity (float sensitivity) {
	}

	@Override
	public String getName () {
		return name;
	}
}
