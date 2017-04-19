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

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.LongMap;
import org.lwjgl.glfw.GLFW;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;

/**
 * Utility class for managing main <--> render thread communication.
 * <p>
 * Provides a bi-directional queue for runnables/delegates between main and render thread. Messages from main to
 * render thread are always executed asynchronously. Messages from render to main thread can block and wait for a
 * function result.
 * <p>
 * This class also provides some utility to manage GL context switches through {@link GLFW#glfwMakeContextCurrent}.
 */
public class Lwjgl3Runnables {

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
	public interface WindowDelegateFunction<R> {

		R call(long window);
	}

	/**
	 * Add hash table entries for the given context, which is either a GLFW window handle, or 0L for the queue
	 * not bound to any window.
	 */
	static void registerContext(long context) {
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.put(context, new Array<>());
		}
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.put(context, new Array<>());
		}
	}

	/**
	 * Remove hash table entries for the given context, which is either a GLFW window handle, or 0L for the queue
	 * not bound to any window.
	 */
	static void unregisterContext(long context) {
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.remove(context);
		}
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.remove(context);
		}
	}

	/**
	 * Queues a {@link Runnable} for execution on the main thread.
	 * <p>
	 * This is a non-blocking call.
	 */
	public static void __post_main(Runnable runnable) {
		delegateToMainThread(0L, context -> runnable.run());
	}

	/**
	 * Queues a {@link WindowDelegate} for execution on the main thread, in context of the specified GLFW window.
	 * <p>
	 * This is a non-blocking call.
	 */
	static void __post_main(long window, WindowDelegate delegate) {
		delegateToMainThread(window, delegate);
	}

	/**
	 * Queues a {@link WindowDelegate} for execution on the main thread, in context of the specified window.
	 * <p>
	 * This is a non-blocking call.
	 */
	static void __post_main(Lwjgl3Window window, WindowDelegate delegate) {
		long context = window.getWindowHandle();
		delegateToMainThread(context, delegate);
	}

	/**
	 * Queues a {@link WindowDelegateFunction} for execution on the main thread.
	 * <p>
	 * This is a blocking call. The render thread (and therefor, the {@link ApplicationListener}) does not continue
	 * until the function has been executed on the main thread.
	 */
	static <R> R __call_main(WindowDelegateFunction<R> delegate) throws InterruptedException {
		return callMainThread(0L, null, delegate);
	}

	/**
	 * Queues a {@link WindowDelegateFunction} for execution on the main thread.
	 * <p>
	 * This is a blocking call. The render thread (and therefor, the {@link ApplicationListener}) does not continue
	 * until the function has been executed on the main thread.
	 */
	public static <R> R __call_main(R defaultValue, WindowDelegateFunction<R> delegate) {
		return callMainThread(0L, defaultValue, delegate);
	}

	/**
	 * Queues a {@link WindowDelegateFunction} for execution on the main thread, in context of the specified window.
	 * <p>
	 * This is a blocking call. The render thread (and therefor, the {@link ApplicationListener}) does not continue
	 * until the function has been executed on the main thread.
	 */
	static <R> R __call_main(Lwjgl3Window window, WindowDelegateFunction<R> delegate) {
		long context = window.getWindowHandle();
		return callMainThread(context, null, delegate);
	}

	/**
	 * Queues a {@link WindowDelegateFunction} for execution on the main thread, in context of the specified window.
	 * <p>
	 * This is a blocking call. The render thread (and therefor, the {@link ApplicationListener}) does not continue
	 * until the function has been executed on the main thread.
	 */
	static <R> R __call_main(Lwjgl3Window window, R defaultValue, WindowDelegateFunction<R> delegate) {
		long context = window.getWindowHandle();
		return callMainThread(context, defaultValue, delegate);
	}

	/**
	 * Queues a function for asynchronous, non-blocking execution in the main thread.
	 */
	private static void delegateToMainThread(long window, WindowDelegate delegate) {
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.get(window).add(delegate);
			glfwPostEmptyEvent();
		}
	}

	/**
	 * Queues a function for blocking execution in the main thread. Uses a {@link CountDownLatch} to wait until the
	 * result is available.
	 */
	private static <R> R callMainThread(long window, R defaultValue, WindowDelegateFunction<R> delegate) {
		AtomicReference<R> result = new AtomicReference<>(defaultValue);
		CountDownLatch latch = new CountDownLatch(1);
		synchronized (mainThreadDelegates) {
			mainThreadDelegates.get(window).add(context -> {
				R r = delegate.call(context);
				result.set(r);
				latch.countDown();
			});
			glfwPostEmptyEvent();
		}
		try {
			latch.await();
		} catch (InterruptedException e) {
			result.set(defaultValue);
		}
		return result.get();
	}

	/**
	 * Runs all delegates queued up for execution on the main thread, which are not bound to any window.
	 */
	static void executeMainThreadDelegates() {
		executeMainThreadDelegates(0L);
	}

	/**
	 * Runs all delegates queued up for execution on the main thread, in context of the specified window.
	 */
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

	/**
	 * Makes the window's GL context current in the main thread.
	 *
	 * @throws ConcurrentModificationException if the context is already current in the render thread
	 */
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

	/**
	 * Queues a {@link Runnable} for execution on the render thread.
	 * <p>
	 * This call does not block the main thread.
	 */
	public static void __post_render(Runnable runnable) {
		delegateToRenderThread(0L, runnable);
	}

	/**
	 * Queues a {@link Runnable} for execution on the render thread, in context of the specified GLFW window.
	 * <p>
	 * This call does not block the main thread.
	 */
	static void __post_render(long window, Runnable runnable) {
		delegateToRenderThread(window, runnable);
	}

	/**
	 * Queues a {@link Runnable} for execution on the render thread, in context of the specified window.
	 * <p>
	 * This call does not block the main thread.
	 */
	static void __post_render(Lwjgl3Window window, Runnable runnable) {
		long context = window.getWindowHandle();
		delegateToRenderThread(context, runnable);
	}

	private static void delegateToRenderThread(long window, Runnable runnable) {
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.get(window).add(runnable);
		}
	}

	/**
	 * Executes all runnables queued up on the render thread, which are not bound to any window.
	 */
	static int executeRenderThreadRunnables() {
		return executeRenderThreadRunnables(0L);
	}

	/**
	 * Executes all runnables queued up on the render thread, in context of the specified window.
	 */
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

	/**
	 * Makes the window's GL context current in the render thread.
	 *
	 * @throws ConcurrentModificationException if the context is already current in the main thread
	 */
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
