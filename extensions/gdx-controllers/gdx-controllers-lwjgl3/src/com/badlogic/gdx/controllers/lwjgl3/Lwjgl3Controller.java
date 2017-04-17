package com.badlogic.gdx.controllers.lwjgl3;

import com.badlogic.gdx.controllers.*;
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
	final boolean[] buttonState;
	final String name;

	public Lwjgl3Controller (Lwjgl3ControllerManager manager, int index) {
		this.manager = manager;
		this.index = index;
		this.axisState = new float[glfwGetJoystickAxes(index).limit()];
		this.buttonState = new boolean[glfwGetJoystickButtons(index).limit()];
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

		__post_render(() -> update(axes, buttons));

		return true;
	}

	private void update (FloatBuffer axes, ByteBuffer buttons) {
		for (int i = 0; i < axes.limit(); i++) {
			if (axisState[i] != axes.get(i)) {
				for (ControllerListener listener : listeners) {
					listener.axisMoved(this, i, axes.get(i));
				}
				manager.axisChanged(this, i, axes.get(i));
			}
			axisState[i] = axes.get(i);
		}

		for (int i = 0; i < buttons.limit(); i++) {
			if (buttonState[i] != (buttons.get(i) == GLFW_PRESS)) {
				for (ControllerListener listener : listeners) {
					if (buttons.get(i) == GLFW_PRESS) {
						listener.buttonDown(this, i);
					} else {
						listener.buttonUp(this, i);
					}
				}
				manager.buttonChanged(this, i, buttons.get(i) == GLFW_PRESS);
			}
			buttonState[i] = buttons.get(i) == GLFW_PRESS;
		}
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
		return buttonState[buttonCode];
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
