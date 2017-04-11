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

import com.badlogic.gdx.utils.Disposable;

import java.util.function.Supplier;

public class Lwjgl3Window2 implements Disposable {

	private final Lwjgl3Application2 application;

	private long handle;

	Lwjgl3Window2(Lwjgl3Application2 application) {
		this.application = application;
	}

	@Override
	public void dispose() {

	}

	Lwjgl3Application2 getApplication() {
		return application;
	}

	public long getWindowHandle() {
		return handle;
	}

	void postRunnable(Runnable runnable) {
		application.postRunnable(runnable);
	}

	void postMainThreadRunnable(Runnable runnable) {
		application.postMainThreadRunnable(runnable);
	}

	<R> R postMainThreadRunnable(Supplier<R> runnable) throws InterruptedException {
		return application.postMainThreadRunnable(runnable);
	}

}
