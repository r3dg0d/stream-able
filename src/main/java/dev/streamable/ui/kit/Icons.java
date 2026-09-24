package dev.streamable.ui.kit;

/**
 * Procedural icons, drawn with the rounded-rect primitive so they stay crisp
 * at every GUI scale without shipping bitmap sprites.
 */
public final class Icons {

    public enum Icon {
        RECORD, STOP, LIVE, PAUSE, PLAY, MIC, MIC_OFF, SPEAKER, PLUS, CLOSE, CHECK, CHEVRON_RIGHT, CHEVRON_DOWN,
        GEAR, EYE, EYE_OFF, LOCK, UNLOCK, UP, DOWN, REFRESH, COPY, WARNING, INFO, HOME, SOURCES, VIDEO, AUDIO,
        DESTINATIONS, HEALTH, RUNTIME, ADVANCED, STREAM, TEST, GRID
    }

    private Icons() {
    }

    /** Draws an icon centred in a square of {@code size} at (x, y). */
    public static void draw(Painter p, Icon icon, float x, float y, float size, int color) {
        float cx = x + size / 2f;
        float cy = y + size / 2f;
        float s = size;
        float w = Math.max(1f, s * 0.12f);
        switch (icon) {
            case RECORD, LIVE -> p.circle(cx, cy, s * 0.34f, color);
            case STOP -> p.roundRect(cx - s * 0.3f, cy - s * 0.3f, s * 0.6f, s * 0.6f, s * 0.08f, color);
            case PAUSE -> {
                p.roundRect(cx - s * 0.3f, cy - s * 0.32f, s * 0.2f, s * 0.64f, 0.8f, color);
                p.roundRect(cx + s * 0.1f, cy - s * 0.32f, s * 0.2f, s * 0.64f, 0.8f, color);
            }
            case PLAY -> {
                p.line(cx - s * 0.2f, cy - s * 0.32f, cx + s * 0.3f, cy, w * 1.4f, color);
                p.line(cx + s * 0.3f, cy, cx - s * 0.2f, cy + s * 0.32f, w * 1.4f, color);
                p.line(cx - s * 0.2f, cy + s * 0.32f, cx - s * 0.2f, cy - s * 0.32f, w * 1.4f, color);
            }
            case MIC, MIC_OFF -> {
                p.roundRect(cx - s * 0.16f, cy - s * 0.42f, s * 0.32f, s * 0.56f, s * 0.16f, color);
                p.roundBorder(cx - s * 0.28f, cy - s * 0.12f, s * 0.56f, s * 0.36f, s * 0.2f, w, color);
                p.fill(Math.round(cx - w / 2), Math.round(cy + s * 0.24f), Math.max(1, Math.round(w)), Math.round(s * 0.18f), color);
                if (icon == Icon.MIC_OFF) {
                    p.line(cx - s * 0.4f, cy - s * 0.4f, cx + s * 0.4f, cy + s * 0.4f, w * 1.3f, color);
                }
            }
            case SPEAKER, AUDIO -> {
                p.roundRect(cx - s * 0.38f, cy - s * 0.14f, s * 0.2f, s * 0.28f, 0.6f, color);
                p.line(cx - s * 0.18f, cy - s * 0.14f, cx + s * 0.06f, cy - s * 0.36f, w, color);
                p.line(cx - s * 0.18f, cy + s * 0.14f, cx + s * 0.06f, cy + s * 0.36f, w, color);
                p.line(cx + s * 0.06f, cy - s * 0.36f, cx + s * 0.06f, cy + s * 0.36f, w, color);
                p.line(cx + s * 0.2f, cy - s * 0.16f, cx + s * 0.28f, cy, w, color);
                p.line(cx + s * 0.28f, cy, cx + s * 0.2f, cy + s * 0.16f, w, color);
            }
            case PLUS -> {
                p.line(cx - s * 0.32f, cy, cx + s * 0.32f, cy, w * 1.2f, color);
                p.line(cx, cy - s * 0.32f, cx, cy + s * 0.32f, w * 1.2f, color);
            }
            case CLOSE -> {
                p.line(cx - s * 0.28f, cy - s * 0.28f, cx + s * 0.28f, cy + s * 0.28f, w * 1.2f, color);
                p.line(cx - s * 0.28f, cy + s * 0.28f, cx + s * 0.28f, cy - s * 0.28f, w * 1.2f, color);
            }
            case CHECK -> {
                p.line(cx - s * 0.32f, cy, cx - s * 0.08f, cy + s * 0.24f, w * 1.3f, color);
                p.line(cx - s * 0.08f, cy + s * 0.24f, cx + s * 0.34f, cy - s * 0.26f, w * 1.3f, color);
            }
            case CHEVRON_RIGHT -> {
                p.line(cx - s * 0.1f, cy - s * 0.28f, cx + s * 0.16f, cy, w * 1.2f, color);
                p.line(cx + s * 0.16f, cy, cx - s * 0.1f, cy + s * 0.28f, w * 1.2f, color);
            }
            case CHEVRON_DOWN -> {
                p.line(cx - s * 0.28f, cy - s * 0.1f, cx, cy + s * 0.16f, w * 1.2f, color);
                p.line(cx, cy + s * 0.16f, cx + s * 0.28f, cy - s * 0.1f, w * 1.2f, color);
            }
            case UP -> {
                p.line(cx - s * 0.28f, cy + s * 0.12f, cx, cy - s * 0.16f, w * 1.2f, color);
                p.line(cx, cy - s * 0.16f, cx + s * 0.28f, cy + s * 0.12f, w * 1.2f, color);
            }
            case DOWN -> draw(p, Icon.CHEVRON_DOWN, x, y, size, color);
            case GEAR, ADVANCED -> {
                p.roundBorder(cx - s * 0.24f, cy - s * 0.24f, s * 0.48f, s * 0.48f, s * 0.24f, w * 1.4f, color);
                for (int i = 0; i < 4; i++) {
                    double a = i * Math.PI / 4;
                    float dx = (float) Math.cos(a) * s * 0.4f;
                    float dy = (float) Math.sin(a) * s * 0.4f;
                    p.line(cx - dx, cy - dy, cx + dx, cy + dy, w * 1.4f, color);
                }
                p.circle(cx, cy, s * 0.1f, color);
            }
            case EYE, EYE_OFF -> {
                p.roundBorder(cx - s * 0.4f, cy - s * 0.2f, s * 0.8f, s * 0.4f, s * 0.2f, w, color);
                p.circle(cx, cy, s * 0.12f, color);
                if (icon == Icon.EYE_OFF) {
                    p.line(cx - s * 0.38f, cy - s * 0.34f, cx + s * 0.38f, cy + s * 0.34f, w * 1.2f, color);
                }
            }
            case LOCK, UNLOCK -> {
                p.roundRect(cx - s * 0.3f, cy - s * 0.04f, s * 0.6f, s * 0.42f, s * 0.08f, color);
                float shackle = icon == Icon.UNLOCK ? s * 0.12f : 0;
                p.roundBorder(cx - s * 0.2f + shackle, cy - s * 0.38f, s * 0.4f, s * 0.44f, s * 0.2f, w * 1.2f, color);
            }
            case REFRESH -> {
                p.roundBorder(cx - s * 0.3f, cy - s * 0.3f, s * 0.6f, s * 0.6f, s * 0.3f, w * 1.2f, color);
                p.roundRect(cx + s * 0.1f, cy - s * 0.42f, s * 0.26f, s * 0.2f, 0.5f, color);
            }
            case COPY -> {
                p.roundBorder(cx - s * 0.32f, cy - s * 0.2f, s * 0.44f, s * 0.52f, s * 0.08f, w, color);
                p.roundBorder(cx - s * 0.12f, cy - s * 0.36f, s * 0.44f, s * 0.52f, s * 0.08f, w, color);
            }
            case WARNING -> {
                p.line(cx, cy - s * 0.4f, cx + s * 0.4f, cy + s * 0.34f, w * 1.2f, color);
                p.line(cx + s * 0.4f, cy + s * 0.34f, cx - s * 0.4f, cy + s * 0.34f, w * 1.2f, color);
                p.line(cx - s * 0.4f, cy + s * 0.34f, cx, cy - s * 0.4f, w * 1.2f, color);
                p.line(cx, cy - s * 0.12f, cx, cy + s * 0.1f, w * 1.2f, color);
                p.circle(cx, cy + s * 0.22f, w * 0.7f, color);
            }
            case INFO -> {
                p.roundBorder(cx - s * 0.4f, cy - s * 0.4f, s * 0.8f, s * 0.8f, s * 0.4f, w, color);
                p.line(cx, cy - s * 0.04f, cx, cy + s * 0.22f, w * 1.2f, color);
                p.circle(cx, cy - s * 0.2f, w * 0.7f, color);
            }
            case HOME -> {
                p.line(cx - s * 0.4f, cy - s * 0.02f, cx, cy - s * 0.4f, w * 1.2f, color);
                p.line(cx, cy - s * 0.4f, cx + s * 0.4f, cy - s * 0.02f, w * 1.2f, color);
                p.roundBorder(cx - s * 0.28f, cy - s * 0.08f, s * 0.56f, s * 0.46f, 0.8f, w * 1.2f, color);
            }
            case SOURCES, GRID -> {
                float g = s * 0.36f;
                p.roundRect(cx - g - 0.5f, cy - g - 0.5f, g, g, 1f, color);
                p.roundRect(cx + 0.5f, cy - g - 0.5f, g, g, 1f, color);
                p.roundRect(cx - g - 0.5f, cy + 0.5f, g, g, 1f, color);
                p.roundRect(cx + 0.5f, cy + 0.5f, g, g, 1f, color);
            }
            case VIDEO -> {
                p.roundBorder(cx - s * 0.42f, cy - s * 0.26f, s * 0.58f, s * 0.52f, s * 0.08f, w * 1.2f, color);
                p.line(cx + s * 0.2f, cy - s * 0.1f, cx + s * 0.42f, cy - s * 0.24f, w * 1.2f, color);
                p.line(cx + s * 0.42f, cy - s * 0.24f, cx + s * 0.42f, cy + s * 0.24f, w * 1.2f, color);
                p.line(cx + s * 0.42f, cy + s * 0.24f, cx + s * 0.2f, cy + s * 0.1f, w * 1.2f, color);
            }
            case DESTINATIONS, STREAM -> {
                p.circle(cx, cy, s * 0.12f, color);
                p.roundBorder(cx - s * 0.28f, cy - s * 0.28f, s * 0.56f, s * 0.56f, s * 0.28f, w, Theme.withAlpha(color, 0.8f));
                p.roundBorder(cx - s * 0.44f, cy - s * 0.44f, s * 0.88f, s * 0.88f, s * 0.44f, w, Theme.withAlpha(color, 0.45f));
            }
            case HEALTH -> {
                p.line(cx - s * 0.44f, cy, cx - s * 0.2f, cy, w * 1.2f, color);
                p.line(cx - s * 0.2f, cy, cx - s * 0.06f, cy - s * 0.32f, w * 1.2f, color);
                p.line(cx - s * 0.06f, cy - s * 0.32f, cx + s * 0.1f, cy + s * 0.32f, w * 1.2f, color);
                p.line(cx + s * 0.1f, cy + s * 0.32f, cx + s * 0.22f, cy, w * 1.2f, color);
                p.line(cx + s * 0.22f, cy, cx + s * 0.44f, cy, w * 1.2f, color);
            }
            case RUNTIME -> {
                p.roundBorder(cx - s * 0.38f, cy - s * 0.3f, s * 0.76f, s * 0.24f, 1f, w, color);
                p.roundBorder(cx - s * 0.38f, cy + s * 0.06f, s * 0.76f, s * 0.24f, 1f, w, color);
                p.circle(cx + s * 0.24f, cy - s * 0.18f, w * 0.7f, color);
                p.circle(cx + s * 0.24f, cy + s * 0.18f, w * 0.7f, color);
            }
            case TEST -> {
                p.roundBorder(cx - s * 0.38f, cy - s * 0.38f, s * 0.76f, s * 0.76f, s * 0.38f, w, color);
                p.line(cx, cy, cx + s * 0.2f, cy - s * 0.22f, w * 1.3f, color);
                p.circle(cx, cy, w, color);
            }
        }
    }
}
