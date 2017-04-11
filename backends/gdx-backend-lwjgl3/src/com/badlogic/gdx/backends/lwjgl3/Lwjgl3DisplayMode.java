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

import static com.badlogic.gdx.Graphics.DisplayMode;

public class Lwjgl3DisplayMode extends DisplayMode {

	private final Lwjgl3Monitor monitor;

	Lwjgl3DisplayMode(Lwjgl3Monitor monitor, int width, int height, int refreshRate, int bitsPerPixel) {
		super(width, height, refreshRate, bitsPerPixel);
		this.monitor = monitor;
	}

	public long getMonitorHandle() {
		return monitor.getMonitorHandle();
	}

}
