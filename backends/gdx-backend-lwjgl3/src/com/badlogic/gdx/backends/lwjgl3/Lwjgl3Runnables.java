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

import com.badlogic.gdx.utils.Array;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;

/**
 * Shared by {@link Lwjgl3Application} and {@link Lwjgl3Window}.
 * <p>
 * Provides a bi-directional messaging queue between main and render thread. Messages to the render thread
 * are always asynchronous. Messages to the main thread can block and wait for a result, if needed.
 */
class Lwjgl3Runnables {

	private final Array<Runnable> renderThreadRunnables = new Array<>();
	private final Array<Runnable> renderThreadRunnablesExecuted = new Array<>();

	private final Array<Runnable> mainThreadRunnables = new Array<>();
	private final Array<Runnable> mainThreadRunnablesExecuted = new Array<>();

	/**
	 * Queues a non-blocking function for execution in the render thread.
	 */
	void postRenderThreadRunnable(Runnable runnable) {
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.add(runnable);
		}
	}

	int executeRenderThreadRunnables() {
		renderThreadRunnablesExecuted.clear();

		synchronized (renderThreadRunnables) {
			renderThreadRunnablesExecuted.addAll(renderThreadRunnables);
			renderThreadRunnables.clear();
		}

		for (Runnable runnable : renderThreadRunnablesExecuted) {
			runnable.run();
		}

		return renderThreadRunnablesExecuted.size;
	}

	/**
	 * Queues a non-blocking function for execution in the main thread.
	 */
	void postMainThreadRunnable(Runnable runnable) {
		synchronized (mainThreadRunnables) {
			mainThreadRunnables.add(runnable);
			glfwPostEmptyEvent();
		}
	}

	/**
	 * Queues a blocking function for execution in the main thread.
	 * <p>
	 * Uses a {@link CountDownLatch} to wait for execution. The calling thread (which should be the render thread
	 * in most use cases) blocks until the function result is available.
	 * <p>
	 * Throws an {@link InterruptedException} in case of an error.
	 */
	<R> R postMainThreadRunnable(Supplier<R> runnable) throws InterruptedException {
		AtomicReference<R> result = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		synchronized (mainThreadRunnables) {
			mainThreadRunnables.add(() -> {
				result.set(runnable.get());
				latch.countDown();
			});
			glfwPostEmptyEvent();
		}
		latch.await();
		return result.get();
	}

	void executeMainThreadRunnables() {
		mainThreadRunnablesExecuted.clear();
		synchronized (mainThreadRunnables) {
			mainThreadRunnablesExecuted.addAll(mainThreadRunnables);
			mainThreadRunnables.clear();
		}
		for (Runnable runnable : mainThreadRunnablesExecuted) {
			runnable.run();
		}
	}

}
