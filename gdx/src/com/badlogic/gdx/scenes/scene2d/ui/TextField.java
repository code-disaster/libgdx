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

package com.badlogic.gdx.scenes.scene2d.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.GlyphLayout.GlyphRun;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Disableable;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.TimeUtils;
import com.badlogic.gdx.utils.Timer;
import com.badlogic.gdx.utils.Timer.Task;

/** A single-line text input field.
 * <p>
 * The preferred height of a text field is the height of the {@link TextFieldStyle#font} and {@link TextFieldStyle#background}.
 * The preferred width of a text field is 150, a relatively arbitrary size.
 * <p>
 * The text field will copy the currently selected text when ctrl+c is pressed, and paste any text in the clipboard when ctrl+v is
 * pressed. Clipboard functionality is provided via the {@link Clipboard} interface. Currently there are two standard
 * implementations, one for the desktop and one for Android. The Android clipboard is a stub, as copy & pasting on Android is not
 * supported yet.
 * <p>
 * The text field allows you to specify an {@link OnscreenKeyboard} for displaying a softkeyboard and piping all key events
 * generated by the keyboard to the text field. There are two standard implementations, one for the desktop and one for Android.
 * The desktop keyboard is a stub, as a softkeyboard is not needed on the desktop. The Android {@link OnscreenKeyboard}
 * implementation will bring up the default IME.
 * @author mzechner
 * @author Nathan Sweet */
public class TextField extends Widget implements Disableable {
	static private final char BACKSPACE = 8;
	static protected final char ENTER_DESKTOP = '\r';
	static protected final char ENTER_ANDROID = '\n';
	static private final char TAB = '\t';
	static private final char DELETE = 127;
	static private final char BULLET = 149;

	static private final Vector2 tmp1 = new Vector2();
	static private final Vector2 tmp2 = new Vector2();
	static private final Vector2 tmp3 = new Vector2();

	static public float keyRepeatInitialTime = 0.4f;
	static public float keyRepeatTime = 0.1f;

	protected String text;
	protected int cursor, selectionStart;
	protected boolean hasSelection;
	protected boolean writeEnters;
	protected final GlyphLayout layout = new GlyphLayout();
	protected final FloatArray glyphPositions = new FloatArray();

	TextFieldStyle style;
	private String messageText;
	protected CharSequence displayText;
	Clipboard clipboard;
	InputListener inputListener;
	TextFieldListener listener;
	TextFieldFilter filter;
	OnscreenKeyboard keyboard = new DefaultOnscreenKeyboard();
	boolean focusTraversal = true, onlyFontChars = true, disabled;
	private int textHAlign = Align.left;
	private float selectionX, selectionWidth;

	String undoText = "";
	long lastChangeTime;

	boolean passwordMode;
	private StringBuilder passwordBuffer;
	private char passwordCharacter = BULLET;

	protected float fontOffset, textHeight, textOffset;
	float renderOffset;
	private int visibleTextStart, visibleTextEnd;
	private int maxLength = 0;

	private float blinkTime = 0.32f;
	boolean cursorOn = true;
	long lastBlink;

	KeyRepeatTask keyRepeatTask = new KeyRepeatTask();
	boolean programmaticChangeEvents;

	public TextField (String text, Skin skin) {
		this(text, skin.get(TextFieldStyle.class));
	}

	public TextField (String text, Skin skin, String styleName) {
		this(text, skin.get(styleName, TextFieldStyle.class));
	}

	public TextField (String text, TextFieldStyle style) {
		setStyle(style);
		clipboard = Gdx.app.getClipboard();
		initialize();
		setText(text);
		setSize(getPrefWidth(), getPrefHeight());
	}

	protected void initialize () {
		addListener(inputListener = createInputListener());
	}

	protected InputListener createInputListener () {
		return new TextFieldClickListener();
	}

	protected int letterUnderCursor (float x) {
		x -= textOffset + fontOffset - style.font.getData().cursorX - glyphPositions.get(visibleTextStart);
		Drawable background = getBackgroundDrawable();
		if (background != null) x -= style.background.getLeftWidth();
		int n = this.glyphPositions.size;
		float[] glyphPositions = this.glyphPositions.items;
		for (int i = 1; i < n; i++) {
			if (glyphPositions[i] > x) {
				if (glyphPositions[i] - x <= x - glyphPositions[i - 1]) return i;
				return i - 1;
			}
		}
		return n - 1;
	}

	protected boolean isWordCharacter (char c) {
		return Character.isLetterOrDigit(c);
	}

	protected int[] wordUnderCursor (int at) {
		String text = this.text;
		int start = at, right = text.length(), left = 0, index = start;
		if (at >= text.length()) {
			left = text.length();
			right = 0;
		} else {
			for (; index < right; index++) {
				if (!isWordCharacter(text.charAt(index))) {
					right = index;
					break;
				}
			}
			for (index = start - 1; index > -1; index--) {
				if (!isWordCharacter(text.charAt(index))) {
					left = index + 1;
					break;
				}
			}
		}
		return new int[] {left, right};
	}

	int[] wordUnderCursor (float x) {
		return wordUnderCursor(letterUnderCursor(x));
	}

	boolean withinMaxLength (int size) {
		return maxLength <= 0 || size < maxLength;
	}

	public void setMaxLength (int maxLength) {
		this.maxLength = maxLength;
	}

	public int getMaxLength () {
		return this.maxLength;
	}

	/** When false, text set by {@link #setText(String)} may contain characters not in the font, a space will be displayed instead.
	 * When true (the default), characters not in the font are stripped by setText. Characters not in the font are always stripped
	 * when typed or pasted. */
	public void setOnlyFontChars (boolean onlyFontChars) {
		this.onlyFontChars = onlyFontChars;
	}

	public void setStyle (TextFieldStyle style) {
		if (style == null) throw new IllegalArgumentException("style cannot be null.");
		this.style = style;
		textHeight = style.font.getCapHeight() - style.font.getDescent() * 2;
		invalidateHierarchy();
	}

	/** Returns the text field's style. Modifying the returned style may not have an effect until {@link #setStyle(TextFieldStyle)}
	 * is called. */
	public TextFieldStyle getStyle () {
		return style;
	}

	protected void calculateOffsets () {
		float visibleWidth = getWidth();
		Drawable background = getBackgroundDrawable();
		if (background != null) visibleWidth -= background.getLeftWidth() + background.getRightWidth();

		int glyphCount = glyphPositions.size;
		float[] glyphPositions = this.glyphPositions.items;

		// Check if the cursor has gone out the left or right side of the visible area and adjust renderOffset.
		float distance = glyphPositions[Math.max(0, cursor - 1)] + renderOffset;
		if (distance <= 0)
			renderOffset -= distance;
		else {
			int index = Math.min(glyphCount - 1, cursor + 1);
			float minX = glyphPositions[index] - visibleWidth;
			if (-renderOffset < minX) renderOffset = -minX;
		}

		// Prevent renderOffset from starting too close to the end, eg after text was deleted.
		float maxOffset = 0;
		float width = glyphPositions[glyphCount - 1];
		for (int i = glyphCount - 2; i >= 0; i--) {
			float x = glyphPositions[i];
			if (width - x > visibleWidth) break;
			maxOffset = x;
		}
		if (-renderOffset > maxOffset) renderOffset = -maxOffset;

		// calculate first visible char based on render offset
		visibleTextStart = 0;
		float startX = 0;
		for (int i = 0; i < glyphCount; i++) {
			if (glyphPositions[i] >= -renderOffset) {
				visibleTextStart = Math.max(0, i);
				startX = glyphPositions[i];
				break;
			}
		}

		// calculate last visible char based on visible width and render offset
		int length = Math.min(displayText.length(), glyphPositions.length - 1);
		visibleTextEnd = Math.min(length, cursor + 1);
		for (; visibleTextEnd <= length; visibleTextEnd++)
			if (glyphPositions[visibleTextEnd] > startX + visibleWidth) break;
		visibleTextEnd = Math.max(0, visibleTextEnd - 1);

		if ((textHAlign & Align.left) == 0) {
			textOffset = visibleWidth - (glyphPositions[visibleTextEnd] - startX);
			if ((textHAlign & Align.center) != 0) textOffset = Math.round(textOffset * 0.5f);
		} else
			textOffset = startX + renderOffset;

		// calculate selection x position and width
		if (hasSelection) {
			int minIndex = Math.min(cursor, selectionStart);
			int maxIndex = Math.max(cursor, selectionStart);
			float minX = Math.max(glyphPositions[minIndex] - glyphPositions[visibleTextStart], -textOffset);
			float maxX = Math.min(glyphPositions[maxIndex] - glyphPositions[visibleTextStart], visibleWidth - textOffset);
			selectionX = minX;
			selectionWidth = maxX - minX - style.font.getData().cursorX;
		}
	}

	private Drawable getBackgroundDrawable () {
		Stage stage = getStage();
		boolean focused = stage != null && stage.getKeyboardFocus() == this;
		return (disabled && style.disabledBackground != null) ? style.disabledBackground
			: ((focused && style.focusedBackground != null) ? style.focusedBackground : style.background);
	}

	@Override
	public void draw (Batch batch, float parentAlpha) {
		Stage stage = getStage();
		boolean focused = stage != null && stage.getKeyboardFocus() == this;
		if (!focused) keyRepeatTask.cancel();

		final BitmapFont font = style.font;
		final Color fontColor = (disabled && style.disabledFontColor != null) ? style.disabledFontColor
			: ((focused && style.focusedFontColor != null) ? style.focusedFontColor : style.fontColor);
		final Drawable selection = style.selection;
		final Drawable cursorPatch = style.cursor;
		final Drawable background = getBackgroundDrawable();

		Color color = getColor();
		float x = getX();
		float y = getY();
		float width = getWidth();
		float height = getHeight();

		batch.setColor(color.r, color.g, color.b, color.a * parentAlpha);
		float bgLeftWidth = 0, bgRightWidth = 0;
		if (background != null) {
			background.draw(batch, x, y, width, height);
			bgLeftWidth = background.getLeftWidth();
			bgRightWidth = background.getRightWidth();
		}

		float textY = getTextY(font, background);
		calculateOffsets();

		if (focused && hasSelection && selection != null) {
			drawSelection(selection, batch, font, x + bgLeftWidth, y + textY);
		}

		float yOffset = font.isFlipped() ? -textHeight : 0;
		if (displayText.length() == 0) {
			if (!focused && messageText != null) {
				if (style.messageFontColor != null) {
					font.setColor(style.messageFontColor.r, style.messageFontColor.g, style.messageFontColor.b,
						style.messageFontColor.a * color.a * parentAlpha);
				} else
					font.setColor(0.7f, 0.7f, 0.7f, color.a * parentAlpha);
				BitmapFont messageFont = style.messageFont != null ? style.messageFont : font;
				messageFont.draw(batch, messageText, x + bgLeftWidth, y + textY + yOffset, 0, messageText.length(),
					width - bgLeftWidth - bgRightWidth, textHAlign, false, "...");
			}
		} else {
			font.setColor(fontColor.r, fontColor.g, fontColor.b, fontColor.a * color.a * parentAlpha);
			drawText(batch, font, x + bgLeftWidth, y + textY + yOffset);
		}
		if (focused && !disabled) {
			blink();
			if (cursorOn && cursorPatch != null) {
				drawCursor(cursorPatch, batch, font, x + bgLeftWidth, y + textY);
			}
		}
	}

	protected float getTextY (BitmapFont font, Drawable background) {
		float height = getHeight();
		float textY = textHeight / 2 + font.getDescent();
		if (background != null) {
			float bottom = background.getBottomHeight();
			textY = textY + (height - background.getTopHeight() - bottom) / 2 + bottom;
		} else {
			textY = textY + height / 2;
		}
		if (font.usesIntegerPositions()) textY = (int)textY;
		return textY;
	}

	/** Draws selection rectangle **/
	protected void drawSelection (Drawable selection, Batch batch, BitmapFont font, float x, float y) {
		selection.draw(batch, x + textOffset + selectionX + fontOffset, y - textHeight - font.getDescent(), selectionWidth,
			textHeight);
	}

	protected void drawText (Batch batch, BitmapFont font, float x, float y) {
		font.draw(batch, displayText, x + textOffset, y, visibleTextStart, visibleTextEnd, 0, Align.left, false);
	}

	protected void drawCursor (Drawable cursorPatch, Batch batch, BitmapFont font, float x, float y) {
		cursorPatch.draw(batch,
			x + textOffset + glyphPositions.get(cursor) - glyphPositions.get(visibleTextStart) + fontOffset + font.getData().cursorX,
			y - textHeight - font.getDescent(), cursorPatch.getMinWidth(), textHeight);
	}

	void updateDisplayText () {
		BitmapFont font = style.font;
		BitmapFontData data = font.getData();
		String text = this.text;
		int textLength = text.length();

		StringBuilder buffer = new StringBuilder();
		for (int i = 0; i < textLength; i++) {
			char c = text.charAt(i);
			buffer.append(data.hasGlyph(c) ? c : ' ');
		}
		String newDisplayText = buffer.toString();

		if (passwordMode && data.hasGlyph(passwordCharacter)) {
			if (passwordBuffer == null) passwordBuffer = new StringBuilder(newDisplayText.length());
			if (passwordBuffer.length() > textLength)
				passwordBuffer.setLength(textLength);
			else {
				for (int i = passwordBuffer.length(); i < textLength; i++)
					passwordBuffer.append(passwordCharacter);
			}
			displayText = passwordBuffer;
		} else
			displayText = newDisplayText;

		layout.setText(font, displayText);
		glyphPositions.clear();
		float x = 0;
		if (layout.runs.size > 0) {
			GlyphRun run = layout.runs.first();
			FloatArray xAdvances = run.xAdvances;
			fontOffset = xAdvances.first();
			for (int i = 1, n = xAdvances.size; i < n; i++) {
				glyphPositions.add(x);
				x += xAdvances.get(i);
			}
		} else
			fontOffset = 0;
		glyphPositions.add(x);

		if (selectionStart > newDisplayText.length()) selectionStart = textLength;
	}

	private void blink () {
		if (!Gdx.graphics.isContinuousRendering()) {
			cursorOn = true;
			return;
		}
		long time = TimeUtils.nanoTime();
		if ((time - lastBlink) / 1000000000.0f > blinkTime) {
			cursorOn = !cursorOn;
			lastBlink = time;
		}
	}

	/** Copies the contents of this TextField to the {@link Clipboard} implementation set on this TextField. */
	public void copy () {
		if (hasSelection && !passwordMode) {
			clipboard.setContents(text.substring(Math.min(cursor, selectionStart), Math.max(cursor, selectionStart)));
		}
	}

	/** Copies the selected contents of this TextField to the {@link Clipboard} implementation set on this TextField, then removes
	 * it. */
	public void cut () {
		cut(programmaticChangeEvents);
	}

	void cut (boolean fireChangeEvent) {
		if (hasSelection && !passwordMode) {
			copy();
			cursor = delete(fireChangeEvent);
			updateDisplayText();
		}
	}

	void paste (String content, boolean fireChangeEvent) {
		if (content == null) return;
		StringBuilder buffer = new StringBuilder();
		int textLength = text.length();
		if (hasSelection) textLength -= Math.abs(cursor - selectionStart);
		BitmapFontData data = style.font.getData();
		for (int i = 0, n = content.length(); i < n; i++) {
			if (!withinMaxLength(textLength + buffer.length())) break;
			char c = content.charAt(i);
			if (!(writeEnters && (c == ENTER_ANDROID || c == ENTER_DESKTOP))) {
				if (c == '\r' || c == '\n') continue;
				if (onlyFontChars && !data.hasGlyph(c)) continue;
				if (filter != null && !filter.acceptChar(this, c)) continue;
			}
			buffer.append(c);
		}
		content = buffer.toString();

		if (hasSelection) cursor = delete(fireChangeEvent);
		if (fireChangeEvent)
			changeText(text, insert(cursor, content, text));
		else
			text = insert(cursor, content, text);
		updateDisplayText();
		cursor += content.length();
	}

	String insert (int position, CharSequence text, String to) {
		if (to.length() == 0) return text.toString();
		return to.substring(0, position) + text + to.substring(position, to.length());
	}

	int delete (boolean fireChangeEvent) {
		int from = selectionStart;
		int to = cursor;
		int minIndex = Math.min(from, to);
		int maxIndex = Math.max(from, to);
		String newText = (minIndex > 0 ? text.substring(0, minIndex) : "")
			+ (maxIndex < text.length() ? text.substring(maxIndex, text.length()) : "");
		if (fireChangeEvent)
			changeText(text, newText);
		else
			text = newText;
		clearSelection();
		return minIndex;
	}

	/** Focuses the next TextField. If none is found, the keyboard is hidden. Does nothing if the text field is not in a stage.
	 * @param up If true, the TextField with the same or next smallest y coordinate is found, else the next highest. */
	public void next (boolean up) {
		Stage stage = getStage();
		if (stage == null) return;
		TextField current = this;
		Vector2 currentCoords = current.getParent().localToStageCoordinates(tmp2.set(current.getX(), current.getY()));
		Vector2 bestCoords = tmp1;
		while (true) {
			TextField textField = current.findNextTextField(stage.getActors(), null, bestCoords, currentCoords, up);
			if (textField == null) { // Try to wrap around.
				if (up)
					currentCoords.set(Float.MIN_VALUE, Float.MIN_VALUE);
				else
					currentCoords.set(Float.MAX_VALUE, Float.MAX_VALUE);
				textField = current.findNextTextField(stage.getActors(), null, bestCoords, currentCoords, up);
			}
			if (textField == null) {
				Gdx.input.setOnscreenKeyboardVisible(false);
				break;
			}
			if (stage.setKeyboardFocus(textField)) break;
			current = textField;
			currentCoords.set(bestCoords);
		}
	}

	/** @return May be null. */
	private TextField findNextTextField (Array<Actor> actors, TextField best, Vector2 bestCoords, Vector2 currentCoords,
		boolean up) {
		for (int i = 0, n = actors.size; i < n; i++) {
			Actor actor = actors.get(i);
			if (actor instanceof TextField) {
				if (actor == this) continue;
				TextField textField = (TextField)actor;
				if (textField.isDisabled() || !textField.focusTraversal) continue;
				Vector2 actorCoords = actor.getParent().localToStageCoordinates(tmp3.set(actor.getX(), actor.getY()));
				boolean below = actorCoords.y != currentCoords.y && (actorCoords.y < currentCoords.y ^ up);
				boolean right = actorCoords.y == currentCoords.y && (actorCoords.x > currentCoords.x ^ up);
				if (!below && !right) continue;
				boolean better = best == null || (actorCoords.y != bestCoords.y && (actorCoords.y > bestCoords.y ^ up));
				if (!better) better = actorCoords.y == bestCoords.y && (actorCoords.x < bestCoords.x ^ up);
				if (better) {
					best = (TextField)actor;
					bestCoords.set(actorCoords);
				}
			} else if (actor instanceof Group)
				best = findNextTextField(((Group)actor).getChildren(), best, bestCoords, currentCoords, up);
		}
		return best;
	}

	public InputListener getDefaultInputListener () {
		return inputListener;
	}

	/** @param listener May be null. */
	public void setTextFieldListener (TextFieldListener listener) {
		this.listener = listener;
	}

	/** @param filter May be null. */
	public void setTextFieldFilter (TextFieldFilter filter) {
		this.filter = filter;
	}

	public TextFieldFilter getTextFieldFilter () {
		return filter;
	}

	/** If true (the default), tab/shift+tab will move to the next text field. */
	public void setFocusTraversal (boolean focusTraversal) {
		this.focusTraversal = focusTraversal;
	}

	/** @return May be null. */
	public String getMessageText () {
		return messageText;
	}

	/** Sets the text that will be drawn in the text field if no text has been entered.
	 * @param messageText may be null. */
	public void setMessageText (String messageText) {
		this.messageText = messageText;
	}

	/** @param str If null, "" is used. */
	public void appendText (String str) {
		if (str == null) str = "";

		clearSelection();
		cursor = text.length();
		paste(str, programmaticChangeEvents);
	}

	/** @param str If null, "" is used. */
	public void setText (String str) {
		if (str == null) str = "";
		if (str.equals(text)) return;

		clearSelection();
		String oldText = text;
		text = "";
		paste(str, false);
		if (programmaticChangeEvents) changeText(oldText, text);
		cursor = 0;
	}

	/** @return Never null, might be an empty string. */
	public String getText () {
		return text;
	}

	/** @param oldText May be null.
	 * @return True if the text was changed. */
	boolean changeText (String oldText, String newText) {
		if (newText.equals(oldText)) return false;
		text = newText;
		ChangeEvent changeEvent = Pools.obtain(ChangeEvent.class);
		boolean cancelled = fire(changeEvent);
		text = cancelled ? oldText : newText;
		Pools.free(changeEvent);
		return !cancelled;
	}

	/** If false, methods that change the text will not fire {@link ChangeEvent}, the event will be fired only when user changes
	 * the text. */
	public void setProgrammaticChangeEvents (boolean programmaticChangeEvents) {
		this.programmaticChangeEvents = programmaticChangeEvents;
	}

	public boolean getProgrammaticChangeEvents () {
		return programmaticChangeEvents;
	}

	public int getSelectionStart () {
		return selectionStart;
	}

	public String getSelection () {
		return hasSelection ? text.substring(Math.min(selectionStart, cursor), Math.max(selectionStart, cursor)) : "";
	}

	/** Sets the selected text. */
	public void setSelection (int selectionStart, int selectionEnd) {
		if (selectionStart < 0) throw new IllegalArgumentException("selectionStart must be >= 0");
		if (selectionEnd < 0) throw new IllegalArgumentException("selectionEnd must be >= 0");
		selectionStart = Math.min(text.length(), selectionStart);
		selectionEnd = Math.min(text.length(), selectionEnd);
		if (selectionEnd == selectionStart) {
			clearSelection();
			return;
		}
		if (selectionEnd < selectionStart) {
			int temp = selectionEnd;
			selectionEnd = selectionStart;
			selectionStart = temp;
		}

		hasSelection = true;
		this.selectionStart = selectionStart;
		cursor = selectionEnd;
	}

	public void selectAll () {
		setSelection(0, text.length());
	}

	public void clearSelection () {
		hasSelection = false;
	}

	/** Sets the cursor position and clears any selection. */
	public void setCursorPosition (int cursorPosition) {
		if (cursorPosition < 0) throw new IllegalArgumentException("cursorPosition must be >= 0");
		clearSelection();
		cursor = Math.min(cursorPosition, text.length());
	}

	public int getCursorPosition () {
		return cursor;
	}

	/** Default is an instance of {@link DefaultOnscreenKeyboard}. */
	public OnscreenKeyboard getOnscreenKeyboard () {
		return keyboard;
	}

	public void setOnscreenKeyboard (OnscreenKeyboard keyboard) {
		this.keyboard = keyboard;
	}

	public void setClipboard (Clipboard clipboard) {
		this.clipboard = clipboard;
	}

	public float getPrefWidth () {
		return 150;
	}

	public float getPrefHeight () {
		float topAndBottom = 0, minHeight = 0;
		if (style.background != null) {
			topAndBottom = Math.max(topAndBottom, style.background.getBottomHeight() + style.background.getTopHeight());
			minHeight = Math.max(minHeight, style.background.getMinHeight());
		}
		if (style.focusedBackground != null) {
			topAndBottom = Math.max(topAndBottom,
				style.focusedBackground.getBottomHeight() + style.focusedBackground.getTopHeight());
			minHeight = Math.max(minHeight, style.focusedBackground.getMinHeight());
		}
		if (style.disabledBackground != null) {
			topAndBottom = Math.max(topAndBottom,
				style.disabledBackground.getBottomHeight() + style.disabledBackground.getTopHeight());
			minHeight = Math.max(minHeight, style.disabledBackground.getMinHeight());
		}
		return Math.max(topAndBottom + textHeight, minHeight);
	}

	/** Sets text horizontal alignment (left, center or right).
	 * @see Align */
	public void setAlignment (int alignment) {
		this.textHAlign = alignment;
	}

	/** If true, the text in this text field will be shown as bullet characters.
	 * @see #setPasswordCharacter(char) */
	public void setPasswordMode (boolean passwordMode) {
		this.passwordMode = passwordMode;
		updateDisplayText();
	}

	public boolean isPasswordMode () {
		return passwordMode;
	}

	/** Sets the password character for the text field. The character must be present in the {@link BitmapFont}. Default is 149
	 * (bullet). */
	public void setPasswordCharacter (char passwordCharacter) {
		this.passwordCharacter = passwordCharacter;
		if (passwordMode) updateDisplayText();
	}

	public void setBlinkTime (float blinkTime) {
		this.blinkTime = blinkTime;
	}

	public void setDisabled (boolean disabled) {
		this.disabled = disabled;
	}

	public boolean isDisabled () {
		return disabled;
	}

	protected void moveCursor (boolean forward, boolean jump) {
		int limit = forward ? text.length() : 0;
		int charOffset = forward ? 0 : -1;
		while ((forward ? ++cursor < limit : --cursor > limit) && jump) {
			if (!continueCursor(cursor, charOffset)) break;
		}
	}

	protected boolean continueCursor (int index, int offset) {
		char c = text.charAt(index + offset);
		return isWordCharacter(c);
	}

	class KeyRepeatTask extends Task {
		int keycode;

		public void run () {
			inputListener.keyDown(null, keycode);
		}
	}

	/** Interface for listening to typed characters.
	 * @author mzechner */
	static public interface TextFieldListener {
		public void keyTyped (TextField textField, char c);
	}

	/** Interface for filtering characters entered into the text field.
	 * @author mzechner */
	static public interface TextFieldFilter {
		public boolean acceptChar (TextField textField, char c);

		static public class DigitsOnlyFilter implements TextFieldFilter {
			@Override
			public boolean acceptChar (TextField textField, char c) {
				return Character.isDigit(c);
			}

		}
	}

	/** An interface for onscreen keyboards. Can invoke the default keyboard or render your own keyboard!
	 * @author mzechner */
	static public interface OnscreenKeyboard {
		public void show (boolean visible);
	}

	/** The default {@link OnscreenKeyboard} used by all {@link TextField} instances. Just uses
	 * {@link Input#setOnscreenKeyboardVisible(boolean)} as appropriate. Might overlap your actual rendering, so use with care!
	 * @author mzechner */
	static public class DefaultOnscreenKeyboard implements OnscreenKeyboard {
		@Override
		public void show (boolean visible) {
			Gdx.input.setOnscreenKeyboardVisible(visible);
		}
	}

	/** Basic input listener for the text field */
	public class TextFieldClickListener extends ClickListener {
		public void clicked (InputEvent event, float x, float y) {
			int count = getTapCount() % 4;
			if (count == 0) clearSelection();
			if (count == 2) {
				int[] array = wordUnderCursor(x);
				setSelection(array[0], array[1]);
			}
			if (count == 3) selectAll();
		}

		public boolean touchDown (InputEvent event, float x, float y, int pointer, int button) {
			if (!super.touchDown(event, x, y, pointer, button)) return false;
			if (pointer == 0 && button != 0) return false;
			if (disabled) return true;
			setCursorPosition(x, y);
			selectionStart = cursor;
			Stage stage = getStage();
			if (stage != null) stage.setKeyboardFocus(TextField.this);
			keyboard.show(true);
			hasSelection = true;
			return true;
		}

		public void touchDragged (InputEvent event, float x, float y, int pointer) {
			super.touchDragged(event, x, y, pointer);
			setCursorPosition(x, y);
		}

		public void touchUp (InputEvent event, float x, float y, int pointer, int button) {
			if (selectionStart == cursor) hasSelection = false;
			super.touchUp(event, x, y, pointer, button);
		}

		protected void setCursorPosition (float x, float y) {
			lastBlink = 0;
			cursorOn = false;
			cursor = letterUnderCursor(x);
		}

		protected void goHome (boolean jump) {
			cursor = 0;
		}

		protected void goEnd (boolean jump) {
			cursor = text.length();
		}

		public boolean keyDown (InputEvent event, int keycode) {
			if (disabled) return false;

			lastBlink = 0;
			cursorOn = false;

			Stage stage = getStage();
			if (stage == null || stage.getKeyboardFocus() != TextField.this) return false;

			boolean repeat = false;
			boolean ctrl = UIUtils.ctrl();
			boolean jump = ctrl && !passwordMode;
			boolean handled = true;

			if (ctrl) {
				switch (keycode) {
				case Keys.V:
					paste(clipboard.getContents(), true);
					repeat = true;
					break;
				case Keys.C:
				case Keys.INSERT:
					copy();
					return true;
				case Keys.X:
					cut(true);
					return true;
				case Keys.A:
					selectAll();
					return true;
				case Keys.Z:
					String oldText = text;
					setText(undoText);
					undoText = oldText;
					updateDisplayText();
					return true;
				default:
					handled = false;
				}
			}

			if (UIUtils.shift()) {
				switch (keycode) {
				case Keys.INSERT:
					paste(clipboard.getContents(), true);
					break;
				case Keys.FORWARD_DEL:
					cut(true);
					break;
				}

				selection:
				{
					int temp = cursor;
					keys:
					{
						switch (keycode) {
						case Keys.LEFT:
							moveCursor(false, jump);
							repeat = true;
							handled = true;
							break keys;
						case Keys.RIGHT:
							moveCursor(true, jump);
							repeat = true;
							handled = true;
							break keys;
						case Keys.HOME:
							goHome(jump);
							handled = true;
							break keys;
						case Keys.END:
							goEnd(jump);
							handled = true;
							break keys;
						}
						break selection;
					}
					if (!hasSelection) {
						selectionStart = temp;
						hasSelection = true;
					}
				}
			} else {
				// Cursor movement or other keys (kills selection).
				switch (keycode) {
				case Keys.LEFT:
					moveCursor(false, jump);
					clearSelection();
					repeat = true;
					handled = true;
					break;
				case Keys.RIGHT:
					moveCursor(true, jump);
					clearSelection();
					repeat = true;
					handled = true;
					break;
				case Keys.HOME:
					goHome(jump);
					clearSelection();
					handled = true;
					break;
				case Keys.END:
					goEnd(jump);
					clearSelection();
					handled = true;
					break;
				}
			}

			cursor = MathUtils.clamp(cursor, 0, text.length());

			if (repeat) scheduleKeyRepeatTask(keycode);
			return handled;
		}

		protected void scheduleKeyRepeatTask (int keycode) {
			if (!keyRepeatTask.isScheduled() || keyRepeatTask.keycode != keycode) {
				keyRepeatTask.keycode = keycode;
				keyRepeatTask.cancel();
				Timer.schedule(keyRepeatTask, keyRepeatInitialTime, keyRepeatTime);
			}
		}

		public boolean keyUp (InputEvent event, int keycode) {
			if (disabled) return false;
			keyRepeatTask.cancel();
			return true;
		}

		public boolean keyTyped (InputEvent event, char character) {
			if (disabled) return false;

			// Disallow "typing" most ASCII control characters, which would show up as a space when onlyFontChars is true.
			switch (character) {
			case BACKSPACE:
			case TAB:
			case ENTER_ANDROID:
			case ENTER_DESKTOP:
				break;
			default:
				if (character < 32) return false;
			}

			Stage stage = getStage();
			if (stage == null || stage.getKeyboardFocus() != TextField.this) return false;

			if (UIUtils.isMac && Gdx.input.isKeyPressed(Keys.SYM)) return true;

			if ((character == TAB || character == ENTER_ANDROID) && focusTraversal) {
				next(UIUtils.shift());
			} else {
				boolean delete = character == DELETE;
				boolean backspace = character == BACKSPACE;
				boolean enter = character == ENTER_DESKTOP || character == ENTER_ANDROID;
				boolean add = enter ? writeEnters : (!onlyFontChars || style.font.getData().hasGlyph(character));
				boolean remove = backspace || delete;
				if (add || remove) {
					String oldText = text;
					int oldCursor = cursor;
					if (hasSelection)
						cursor = delete(false);
					else {
						if (backspace && cursor > 0) {
							text = text.substring(0, cursor - 1) + text.substring(cursor--);
							renderOffset = 0;
						}
						if (delete && cursor < text.length()) {
							text = text.substring(0, cursor) + text.substring(cursor + 1);
						}
					}
					if (add && !remove) {
						// Character may be added to the text.
						if (!enter && filter != null && !filter.acceptChar(TextField.this, character)) return true;
						if (!withinMaxLength(text.length())) return true;
						String insertion = enter ? "\n" : String.valueOf(character);
						text = insert(cursor++, insertion, text);
					}
					String tempUndoText = undoText;
					if (changeText(oldText, text)) {
						long time = System.currentTimeMillis();
						if (time - 750 > lastChangeTime) undoText = oldText;
						lastChangeTime = time;
					} else
						cursor = oldCursor;
					updateDisplayText();
				}
			}
			if (listener != null) listener.keyTyped(TextField.this, character);
			return true;
		}
	}

	/** The style for a text field, see {@link TextField}.
	 * @author mzechner
	 * @author Nathan Sweet */
	static public class TextFieldStyle {
		public BitmapFont font;
		public Color fontColor;
		/** Optional. */
		public Color focusedFontColor, disabledFontColor;
		/** Optional. */
		public Drawable background, focusedBackground, disabledBackground, cursor, selection;
		/** Optional. */
		public BitmapFont messageFont;
		/** Optional. */
		public Color messageFontColor;

		public TextFieldStyle () {
		}

		public TextFieldStyle (BitmapFont font, Color fontColor, Drawable cursor, Drawable selection, Drawable background) {
			this.background = background;
			this.cursor = cursor;
			this.font = font;
			this.fontColor = fontColor;
			this.selection = selection;
		}

		public TextFieldStyle (TextFieldStyle style) {
			this.messageFont = style.messageFont;
			if (style.messageFontColor != null) this.messageFontColor = new Color(style.messageFontColor);
			this.background = style.background;
			this.focusedBackground = style.focusedBackground;
			this.disabledBackground = style.disabledBackground;
			this.cursor = style.cursor;
			this.font = style.font;
			if (style.fontColor != null) this.fontColor = new Color(style.fontColor);
			if (style.focusedFontColor != null) this.focusedFontColor = new Color(style.focusedFontColor);
			if (style.disabledFontColor != null) this.disabledFontColor = new Color(style.disabledFontColor);
			this.selection = style.selection;
		}
	}
}
