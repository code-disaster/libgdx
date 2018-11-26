package com.badlogic.gdx.controllers.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables;
import com.badlogic.gdx.controllers.*;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import org.lwjgl.glfw.GLFWJoystickCallback;

import static com.badlogic.gdx.backends.lwjgl3.Lwjgl3Runnables.*;
import static org.lwjgl.glfw.GLFW.*;

public class Lwjgl3ControllerManager implements ControllerManager, Disposable {

	private final Array<Controller> controllers = new Array<>();
	private final Array<Controller> polledControllers = new Array<>();
	private final Array<ControllerListener> listeners = new Array<>();

	private final GLFWJoystickCallback joystickCallback = new GLFWJoystickCallback() {
		@Override
		public void invoke(int jid, int event) {
			if (event == GLFW_CONNECTED) {
				connected(jid);
			} else if (event == GLFW_DISCONNECTED) {
				disconnected(jid);
			}
		}
	};

	public Lwjgl3ControllerManager() {
		__call_main(null, handle -> {
			glfwSetJoystickCallback(joystickCallback);
			return null;
		});
	}

	@Override
	public void dispose() {
		__call_main(null, handle -> {
			glfwSetJoystickCallback(null);
			joystickCallback.free();
			return null;
		});
	}

	void update() {
		polledControllers.clear();
		synchronized (controllers) {
			polledControllers.addAll(controllers);
		}

		for (Controller controller : polledControllers) {
			Lwjgl3Controller instance = (Lwjgl3Controller) controller;
			if (!instance.update()) {
				disconnected(instance);
			}
		}

		if (polledControllers.size > 0) {
			if (Lwjgl3Runnables.isSeparateRenderThread()) {
				glfwPostEmptyEvent();
			}
		}
	}

	private void connected(int index) {
		connected(new Lwjgl3Controller(this, index));
	}

	void connected(Lwjgl3Controller controller) {
		synchronized (controllers) {
			controllers.add(controller);
		}
		__post_render(() -> {
			for (ControllerListener listener : listeners) {
				listener.connected(controller);
			}
		});
	}

	private void disconnected(int index) {
		synchronized (controllers) {
			for (int i = 0; i < controllers.size; i++) {
				Lwjgl3Controller controller = (Lwjgl3Controller) controllers.get(i);
				if (controller.index == index) {
					disconnected(controller);
					break;
				}
			}
		}
	}

	void disconnected(Lwjgl3Controller controller) {
		synchronized (controllers) {
			controllers.removeValue(controller, true);
		}
		__post_render(() -> {
			for (ControllerListener listener : listeners) {
				listener.disconnected(controller);
			}
		});
	}

	@Override
	public Array<Controller> getControllers() {
		return controllers;
	}

	@Override
	public void addListener(ControllerListener listener) {
		listeners.add(listener);
	}

	@Override
	public void removeListener(ControllerListener listener) {
		listeners.removeValue(listener, true);
	}

	@Override
	public void clearListeners() {
		listeners.clear();
	}

	void axisChanged(Lwjgl3Controller controller, int axisCode, float value) {
		for (ControllerListener listener : listeners) {
			if (listener.axisMoved(controller, axisCode, value)) break;
		}
	}

	void buttonChanged(Lwjgl3Controller controller, int buttonCode, boolean value) {
		for (ControllerListener listener : listeners) {
			if (value) {
				if (listener.buttonDown(controller, buttonCode)) break;
			} else {
				if (listener.buttonUp(controller, buttonCode)) break;
			}
		}
	}

	void hatChanged(Lwjgl3Controller controller, int hatCode, PovDirection value) {
		for (ControllerListener listener : listeners) {
			if (listener.povMoved(controller, hatCode, value)) break;
		}
	}

	@Override
	public Array<ControllerListener> getListeners() {
		return listeners;
	}
}
