package dev.streamable.ui.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A password-style field for stream keys.
 *
 * <p>Hidden by default with an explicit reveal toggle, because a stream key is a
 * credential and a streamer editing settings is, by definition, often on camera.
 * The real value is kept here and only handed to the underlying {@link EditBox}
 * when revealed; while hidden the box shows a dot mask of the same length, so
 * nothing sensitive can be read from a screenshot or a recording.</p>
 *
 * <p>Paste still works while hidden: typed and pasted input is captured and
 * folded into the real value rather than into the mask.</p>
 */
public final class SecretEditBox extends EditBox {

    private String secret = "";
    private boolean revealed;
    private boolean updating;

    public SecretEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        setMaxLength(512);
        setResponder(this::onTextChanged);
    }

    private void onTextChanged(String text) {
        if (updating) {
            return;
        }
        if (revealed) {
            secret = text;
            return;
        }
        // While masked, only edits that change the length are meaningful: a
        // shorter value means characters were deleted, a longer one means new
        // characters were typed or pasted at the end.
        if (text.length() < secret.length()) {
            secret = secret.substring(0, text.length());
        } else if (text.length() > secret.length()) {
            secret += text.substring(secret.length());
        }
        refreshDisplay();
    }

    /** The real value. Never logged, never included in diagnostics. */
    public String secret() {
        return secret;
    }

    public void setSecret(String value) {
        this.secret = value == null ? "" : value;
        refreshDisplay();
    }

    public boolean isRevealed() {
        return revealed;
    }

    public void setRevealed(boolean revealed) {
        this.revealed = revealed;
        refreshDisplay();
    }

    public void toggleRevealed() {
        setRevealed(!revealed);
    }

    public void clearSecret() {
        setSecret("");
    }

    private void refreshDisplay() {
        updating = true;
        try {
            setValue(revealed ? secret : "•".repeat(secret.length()));
        } finally {
            updating = false;
        }
    }
}
