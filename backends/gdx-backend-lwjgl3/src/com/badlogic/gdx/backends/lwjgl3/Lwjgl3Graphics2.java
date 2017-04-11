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

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.utils.Disposable;

public class Lwjgl3Graphics2 implements Graphics, Disposable {

	private final Lwjgl3Window2 window;

	Lwjgl3Graphics2(Lwjgl3Window2 window) {
		this.window = window;
	}

	@Override
	public boolean isGL30Available() {
		return false;
	}

	@Override
	public GL20 getGL20() {
		return null;
	}

	@Override
	public GL30 getGL30() {
		return null;
	}

	@Override
	public int getWidth() {
		return 0;
	}

	@Override
	public int getHeight() {
		return 0;
	}

	@Override
	public int getBackBufferWidth() {
		return 0;
	}

	@Override
	public int getBackBufferHeight() {
		return 0;
	}

	@Override
	public long getFrameId() {
		return 0;
	}

	@Override
	public float getDeltaTime() {
		return 0;
	}

	@Override
	public float getRawDeltaTime() {
		return 0;
	}

	@Override
	public int getFramesPerSecond() {
		return 0;
	}

	@Override
	public GraphicsType getType() {
		return null;
	}

	@Override
	public GLVersion getGLVersion() {
		return null;
	}

	@Override
	public float getPpiX() {
		return 0;
	}

	@Override
	public float getPpiY() {
		return 0;
	}

	@Override
	public float getPpcX() {
		return 0;
	}

	@Override
	public float getPpcY() {
		return 0;
	}

	@Override
	public float getDensity() {
		return 0;
	}

	@Override
	public boolean supportsDisplayModeChange() {
		return false;
	}

	@Override
	public Monitor getPrimaryMonitor() {
		return null;
	}

	@Override
	public Monitor getMonitor() {
		return null;
	}

	@Override
	public Monitor[] getMonitors() {
		return new Monitor[0];
	}

	@Override
	public DisplayMode[] getDisplayModes() {
		return new DisplayMode[0];
	}

	@Override
	public DisplayMode[] getDisplayModes(Monitor monitor) {
		return new DisplayMode[0];
	}

	@Override
	public DisplayMode getDisplayMode() {
		return null;
	}

	@Override
	public DisplayMode getDisplayMode(Monitor monitor) {
		return null;
	}

	@Override
	public boolean setFullscreenMode(DisplayMode displayMode) {
		return false;
	}

	@Override
	public boolean setWindowedMode(int width, int height) {
		return false;
	}

	@Override
	public void setTitle(String title) {

	}

	@Override
	public void setUndecorated(boolean undecorated) {

	}

	@Override
	public void setResizable(boolean resizable) {

	}

	@Override
	public void setVSync(boolean vsync) {

	}

	@Override
	public BufferFormat getBufferFormat() {
		return null;
	}

	@Override
	public boolean supportsExtension(String extension) {
		return false;
	}

	@Override
	public void setContinuousRendering(boolean isContinuous) {

	}

	@Override
	public boolean isContinuousRendering() {
		return false;
	}

	@Override
	public void requestRendering() {

	}

	@Override
	public boolean isFullscreen() {
		return false;
	}

	@Override
	public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
		return new Lwjgl3Cursor2(window, pixmap, xHotspot, yHotspot);
	}

	@Override
	public void setCursor(Cursor cursor) {
		((Lwjgl3Cursor2) cursor).setCursor();
	}

	@Override
	public void setSystemCursor(Cursor.SystemCursor systemCursor) {
		Lwjgl3Cursor2.setSystemCursor(window, systemCursor);
	}

	@Override
	public void dispose() {

	}

}
