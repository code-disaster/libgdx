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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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

	static volatile boolean separateRenderThread = false;
	static final long APPLICATION_CONTEXT = 0L;

	private static final LongMap<Array<WindowDelegate>> mainThreadDelegates = new LongMap<>();
	private static final Array<WindowDelegate> mainThreadDelegatesExecuted = new Array<>();

	private static final LongMap<Array<Runnable>> renderThreadRunnables = new LongMap<>();
	private static final Array<Runnable> renderThreadRunnablesExecuted = new Array<>();

	private static volatile long mainThreadContext = APPLICATION_CONTEXT;
	private static volatile long renderThreadContext = APPLICATION_CONTEXT;
	private static final LongMap<Lock> contextLocks = new LongMap<>();

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
		synchronized (contextLocks) {
			contextLocks.put(context, new ReentrantLock());
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
		synchronized (contextLocks) {
			contextLocks.remove(context);
		}
	}

	/**
	 * Queues a {@link Runnable} for execution on the main thread.
	 * <p>
	 * This is a non-blocking call.
	 */
	public static void __post_main(Runnable runnable) {
		delegateToMainThread(APPLICATION_CONTEXT, context -> runnable.run());
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
		return callMainThread(APPLICATION_CONTEXT, null, delegate);
	}

	/**
	 * Queues a {@link WindowDelegateFunction} for execution on the main thread.
	 * <p>
	 * This is a blocking call. The render thread (and therefor, the {@link ApplicationListener}) does not continue
	 * until the function has been executed on the main thread.
	 */
	public static <R> R __call_main(R defaultValue, WindowDelegateFunction<R> delegate) {
		return callMainThread(APPLICATION_CONTEXT, defaultValue, delegate);
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
	private static void delegateToMainThread(long context, WindowDelegate delegate) {
		if (separateRenderThread) {
			synchronized (mainThreadDelegates) {
				mainThreadDelegates.get(context).add(delegate);
				glfwPostEmptyEvent();
			}
		} else {
			delegate.run(context);
		}
	}

	/**
	 * Queues a function for blocking execution in the main thread. Uses a {@link CountDownLatch} to wait until the
	 * result is available.
	 */
	private static <R> R callMainThread(long context, R defaultValue, WindowDelegateFunction<R> delegate) {
		if (separateRenderThread) {
			AtomicReference<R> result = new AtomicReference<>(defaultValue);
			CountDownLatch latch = new CountDownLatch(1);
			synchronized (mainThreadDelegates) {
				mainThreadDelegates.get(context).add(ctx -> {
					R r = delegate.call(ctx);
					result.set(r);
					latch.countDown();
				});
				glfwPostEmptyEvent();
			}
			long current = renderThreadContext;
			boolean needRelock = context != APPLICATION_CONTEXT && context == current;
			if (needRelock) {
				//
				// We are in the render thread, and in context of a window, so we own its context lock already. If
				// we wait on the latch now, we are running into a deadlock, because the main thread won't get past
				// our context lock while we hold it.
				//
				// To resolve this, we briefly unlock here, wait for the latch countdown, and lock again. While we
				// wait, the main thread does its work, including the runnable above, which gets us past the latch.
				//
				renderThreadContext = APPLICATION_CONTEXT;
				contextLocks.get(context).unlock();
			}
			try {
				latch.await();
			} catch (InterruptedException e) {
				result.set(defaultValue);
			}
			if (needRelock) {
				contextLocks.get(context).lock();
				renderThreadContext = context;
			}
			return result.get();
		} else {
			return delegate.call(context);
		}
	}

	/**
	 * Runs all delegates queued up for execution on the main thread, which are not bound to any window.
	 */
	static void executeMainThreadDelegates() {
		executeMainThreadDelegates(APPLICATION_CONTEXT);
	}

	/**
	 * Runs all delegates queued up for execution on the main thread, in context of the specified window.
	 */
	static void executeMainThreadDelegates(Lwjgl3Window window) {
		long context = window.getWindowHandle();
		executeMainThreadDelegates(context);
	}

	private static void executeMainThreadDelegates(long context) {
		mainThreadDelegatesExecuted.clear();
		synchronized (mainThreadDelegates) {
			Array<WindowDelegate> delegates = mainThreadDelegates.get(context);
			mainThreadDelegatesExecuted.addAll(delegates);
			delegates.clear();
		}
		if (mainThreadDelegatesExecuted.size > 0) {
			__context_main(context);
			for (WindowDelegate delegate : mainThreadDelegatesExecuted) {
				delegate.run(context);
			}
		}
	}

	/**
	 * Makes the window's GL context current in the main thread.
	 *
	 * @throws ConcurrentModificationException if the context is already current in the render thread
	 */
	static void __context_main(long context) {
		setMainThreadContext(context);
	}

	private static void setMainThreadContext(long context) {
		long current = mainThreadContext;
		if (context != current) {
			if (context != APPLICATION_CONTEXT) {
				Lock lock;
				synchronized (contextLocks) {
					lock = contextLocks.get(context);
				}
				lock.lock();
				if (context == renderThreadContext) {
					throw new ConcurrentModificationException("Context already active in render thread");
				}
			}
			glfwMakeContextCurrent(context);
			mainThreadContext = context;
			if (current != APPLICATION_CONTEXT) {
				Lock lock;
				synchronized (contextLocks) {
					lock = contextLocks.get(current);
				}
				if (lock != null) {
					lock.unlock();
				}
			}
		}
	}

	/**
	 * Queues a {@link Runnable} for execution on the render thread.
	 * <p>
	 * This call does not block the main thread.
	 */
	public static void __post_render(Runnable runnable) {
		delegateToRenderThread(APPLICATION_CONTEXT, runnable);
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

	private static void delegateToRenderThread(long context, Runnable runnable) {
		synchronized (renderThreadRunnables) {
			renderThreadRunnables.get(context).add(runnable);
		}
	}

	/**
	 * Executes all runnables queued up on the render thread, which are not bound to any window.
	 */
	static int executeRenderThreadRunnables() {
		return executeRenderThreadRunnables(APPLICATION_CONTEXT);
	}

	/**
	 * Executes all runnables queued up on the render thread, in context of the specified window.
	 */
	static int executeRenderThreadRunnables(Lwjgl3Window window) {
		return executeRenderThreadRunnables(window.getWindowHandle());
	}

	private static int executeRenderThreadRunnables(long context) {
		renderThreadRunnablesExecuted.clear();
		synchronized (renderThreadRunnables) {
			Array<Runnable> runnables = renderThreadRunnables.get(context);
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
	static void __context_render(long context) {
		setRenderThreadContext(context);
	}

	private static void setRenderThreadContext(long context) {
		long current = renderThreadContext;
		if (context != current) {
			if (context != APPLICATION_CONTEXT) {
				Lock lock;
				synchronized (contextLocks) {
					lock = contextLocks.get(context);
				}
				lock.lock();
				if (context == mainThreadContext) {
					throw new ConcurrentModificationException("Context already active in main thread");
				}
			}
			glfwMakeContextCurrent(context);
			renderThreadContext = context;
			if (current != APPLICATION_CONTEXT) {
				Lock lock;
				synchronized (contextLocks) {
					lock = contextLocks.get(current);
				}
				if (lock != null) {
					lock.unlock();
				}
			}
		}
	}

}
