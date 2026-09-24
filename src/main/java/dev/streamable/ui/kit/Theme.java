package dev.streamable.ui.kit;

/**
 * Design tokens for the Studio. Every component reads its spacing, sizes,
 * radii, type and colour from here, so the whole interface stays coherent.
 *
 * <p>Units are GUI pixels (Minecraft's scaled coordinates).</p>
 */
public final class Theme {

    private Theme() {
    }

    // ---- spacing (4-point scale) ----------------------------------------------------
    public static final int SPACE_1 = 2;
    public static final int SPACE_2 = 4;
    public static final int SPACE_3 = 6;
    public static final int SPACE_4 = 8;
    public static final int SPACE_5 = 12;
    public static final int SPACE_6 = 16;
    public static final int SPACE_7 = 24;

    public static final int PANEL_PADDING = 10;
    public static final int SECTION_GAP = 10;

    // ---- radii --------------------------------------------------------------------
    public static final float RADIUS_SMALL = 3f;
    public static final float RADIUS = 5f;
    public static final float RADIUS_LARGE = 8f;

    // ---- control sizes -----------------------------------------------------------------
    public static final int CONTROL_HEIGHT = 16;
    public static final int CONTROL_HEIGHT_LARGE = 20;
    public static final int ICON_SIZE = 8;
    public static final int SIDEBAR_WIDTH = 108;
    public static final int STATUS_BAR_HEIGHT = 18;
    public static final int TOP_BAR_HEIGHT = 24;

    // ---- type scale ----------------------------------------------------------------------
    public static final float TEXT_CAPTION = 0.8f;
    public static final float TEXT_BODY = 1.0f;
    public static final float TEXT_TITLE = 1.25f;
    public static final float TEXT_DISPLAY = 1.6f;

    // ---- motion (seconds) ----------------------------------------------------------------
    public static final float ANIM_FAST = 0.08f;
    public static final float ANIM = 0.14f;
    public static final float ANIM_SLOW = 0.25f;

    // ---- colour (ARGB) -------------------------------------------------------------------
    public static final int BACKDROP = 0xE60B0D12;
    public static final int SURFACE = 0xFF14171E;
    public static final int SURFACE_RAISED = 0xFF1B1F28;
    public static final int SURFACE_HOVER = 0xFF232835;
    public static final int SURFACE_PRESSED = 0xFF2A3040;
    public static final int FIELD = 0xFF0F1218;
    public static final int BORDER = 0x1FFFFFFF;
    public static final int BORDER_STRONG = 0x33FFFFFF;
    public static final int DIVIDER = 0x14FFFFFF;

    public static final int TEXT = 0xFFE8EAF0;
    public static final int TEXT_SECONDARY = 0xFFA6ADBD;
    public static final int TEXT_MUTED = 0xFF6E7687;
    public static final int TEXT_DISABLED = 0xFF4A5060;
    public static final int TEXT_ON_ACCENT = 0xFFFFFFFF;

    public static final int ACCENT = 0xFF6C8CFF;
    public static final int ACCENT_HOVER = 0xFF8099FF;
    public static final int ACCENT_PRESSED = 0xFF5877E8;
    public static final int ACCENT_SOFT = 0x336C8CFF;
    public static final int FOCUS_RING = 0xCC8FA8FF;

    public static final int LIVE = 0xFFFF4757;
    public static final int LIVE_SOFT = 0x33FF4757;
    public static final int RECORDING = 0xFFFF9F43;
    public static final int RECORDING_SOFT = 0x33FF9F43;
    public static final int SUCCESS = 0xFF3DD68C;
    public static final int SUCCESS_SOFT = 0x2E3DD68C;
    public static final int WARNING = 0xFFFFC857;
    public static final int WARNING_SOFT = 0x2EFFC857;
    public static final int DANGER = 0xFFFF5C6C;
    public static final int DANGER_SOFT = 0x2EFF5C6C;
    public static final int INFO = 0xFF5CC8FF;

    public static final int SHADOW = 0x66000000;

    /** Linear interpolation between two ARGB colours. */
    public static int mix(int from, int to, float t) {
        float k = Math.clamp(t, 0f, 1f);
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, float alpha) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.clamp(alpha, 0f, 1f));
        return (a << 24) | (color & 0xFFFFFF);
    }
}
