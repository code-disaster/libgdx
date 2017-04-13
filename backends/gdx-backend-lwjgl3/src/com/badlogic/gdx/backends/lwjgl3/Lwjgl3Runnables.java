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
import com.badlogic.gdx.utils.LongMap;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;

/**
 * Shared by {@link Lwjgl3Application} and {@link Lwjgl3Window}.
 * <p>
 * Provides a bi-directional messaging queue between main and render thread. Messages to the render thread
 * are always asynchronous. Messages to the main thread can block and wait for a result, if needed.
 */
class Lwjgl3Runnables {

	private static final LongMap<Array<WindowDelegate>> mainThreadDelegates = new LongMap<>();
	private static final Array<WindowDelegate> mainThreadDelegatesExecuted = new Array<>();

	private static final LongMap<Array<Runnable>> renderThreadRunnables = new LongMap<>();
	private static final Array<Runnable> renderThreadRunnablesExecuted = new Array<>();

	private static final AtomicLong mainThreadContext = new AtomicLong();
	private static final AtomicLong renderThreadContext = new AtomicLong();

	@FunctionalInterface
	interface WindowDelegate {

		void run(long window);
	}

	@FunctionalInterface
	interface WindowDelegateFunction<R> {

		R call(long window);
	}

	static void registerContext(long context) {
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.put(context, new Array<>());
		}
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.put(context, new Array<>());
		}
	}

	static void unregisterContext(long context) {
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.remove(context);
		}
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.remove(context);
		}
	}

	static void __post_main(Runnable runnable) {
		delegateToMainThread(0L, context -> runnable.run());
	}

	static void __post_main(long window, WindowDelegate delegate) {
		delegateToMainThread(window, delegate);
	}

	static void __post_main(Lwjgl3Window window, WindowDelegate delegate) {
		long context = window.getWindowHandle();
		__post_main(context, delegate);
	}

	static <R> R __call_main(WindowDelegateFunction<R> delegate) throws InterruptedException {
		return callMainThread(0L, delegate);
	}

	static <R> R __call_main(R defaultValue, WindowDelegateFunction<R> delegate) {
		try {
			return __call_main(delegate);
		} catch (InterruptedException e) {
			return defaultValue;
		}
	}

	static <R> R __call_main(Lwjgl3Window window, WindowDelegateFunction<R> delegate) throws InterruptedException {
		long context = window.getWindowHandle();
		return callMainThread(context, delegate);
	}

	static <R> R __call_main(Lwjgl3Window window, R defaultValue, WindowDelegateFunction<R> delegate) {
		try {
			return __call_main(window, delegate);
		} catch (InterruptedException e) {
			return defaultValue;
		}
	}

	/**
	 * Queues a non-blocking function for execution in the main thread.
	 */
	private static void delegateToMainThread(long window, WindowDelegate delegate) {
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.get(window).add(delegate);
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
	private static <R> R callMainThread(long window, WindowDelegateFunction<R> delegate) throws InterruptedException {
		AtomicReference<R> result = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.get(window).add(context -> {
				result.set(delegate.call(context));
				latch.countDown();
			});
			glfwPostEmptyEvent();
		}
		latch.await();
		return result.get();
	}

	static void executeMainThreadDelegates() {
		executeMainThreadDelegates(0L);
	}

	static void executeMainThreadDelegates(Lwjgl3Window window) {
		executeMainThreadDelegates(window.getWindowHandle());
	}

	private static void executeMainThreadDelegates(long window) {
		mainThreadDelegatesExecuted.clear();
		synchronized (mainThreadDelegates) {
			Array<WindowDelegate> delegates = mainThreadDelegates.get(window);
			mainThreadDelegatesExecuted.addAll(delegates);
			delegates.clear();
		}
		for (WindowDelegate delegate : mainThreadDelegatesExecuted) {
			delegate.run(window);
		}
	}

	static void __context_main(long window) {
		setMainThreadContext(window);
	}

	private static void setMainThreadContext(long context) {
		synchronized (mainThreadContext) {
			if (context != 0L && context == renderThreadContext.get()) {
				throw new ConcurrentModificationException("Context already active in render thread");
			}
			long current = mainThreadContext.get();
			if (context != current) {
				glfwMakeContextCurrent(context);
				mainThreadContext.set(context);
			}
		}
	}

	static void __post_render(Runnable runnable) {
		delegateToRenderThread(0L, runnable);
	}

	static void __post_render(long window, Runnable runnable) {
		delegateToRenderThread(window, runnable);
	}

	static void __post_render(Lwjgl3Window window, Runnable runnable) {
		long context = window.getWindowHandle();
		delegateToRenderThread(context, runnable);
	}

	/**
	 * Queues a non-blocking function for execution in the render thread.
	 */
	private static void delegateToRenderThread(long window, Runnable runnable) {
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.get(window).add(runnable);
		}
	}

	static int executeRenderThreadRunnables() {
		return executeRenderThreadRunnables(0L);
	}

	static int executeRenderThreadRunnables(Lwjgl3Window window) {
		return executeRenderThreadRunnables(window.getWindowHandle());
	}

	private static int executeRenderThreadRunnables(long window) {
		renderThreadRunnablesExecuted.clear();
		synchronized (renderThreadRunnables) {
			Array<Runnable> runnables = renderThreadRunnables.get(window);
			renderThreadRunnablesExecuted.addAll(runnables);
			runnables.clear();
		}
		for (Runnable runnable : renderThreadRunnablesExecuted) {
			runnable.run();
		}
		return renderThreadRunnablesExecuted.size;
	}

	static void __context_render(long window) {
		setRenderThreadContext(window);
	}

	private static void setRenderThreadContext(long context) {
		synchronized (mainThreadContext) {
			if (context != 0L && context == mainThreadContext.get()) {
				throw new ConcurrentModificationException("Context already active in main thread");
			}
			long current = renderThreadContext.get();
			if (context != current) {
				glfwMakeContextCurrent(context);
				renderThreadContext.set(context);
			}
		}
	}

}
