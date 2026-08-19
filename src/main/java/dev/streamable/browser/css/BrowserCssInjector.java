package dev.streamable.browser.css;

/**
 * Builds the JavaScript that applies transparency and user CSS to a page.
 *
 * <h2>Why two style blocks</h2>
 * <p>A browser source is only useful as an overlay if the page's own background
 * does not paint an opaque rectangle over the game. Two things are needed and
 * both are done here:</p>
 * <ol>
 *   <li>A <b>transparency base</b> with {@code !important}, so a widget that
 *       ships {@code body { background: #000 }} still composites correctly.</li>
 *   <li>The <b>user's custom CSS</b> in a second block, injected afterwards so
 *       it wins for everything else.</li>
 * </ol>
 *
 * <p>CSS alone is not sufficient - the browser itself must be created with a
 * transparent background and the whole pixel path has to preserve alpha. That
 * part is handled by {@code McefBrowserBackend} (it passes
 * {@code transparent = true}) and by the compositor's premultiplied-alpha
 * blending.</p>
 *
 * <p>Both blocks are re-applied on every load, and are idempotent: they replace
 * their previous element rather than stacking up.</p>
 */
public final class BrowserCssInjector {

    /** Element id for the forced-transparency block. */
    public static final String BASE_STYLE_ID = "streamable-transparency";
    /** Element id for the user's own CSS. */
    public static final String USER_STYLE_ID = "streamable-user-css";

    /**
     * Forced transparency. {@code html} is included as well as {@code body}
     * because a page that only clears {@code body} still shows the root
     * element's default white.
     */
    public static final String TRANSPARENCY_BASE_CSS = """
            html, body {
                background: transparent !important;
                background-color: rgba(0, 0, 0, 0) !important;
            }
            """;

    private BrowserCssInjector() {
    }

    /**
     * Builds a self-contained script that installs both style blocks.
     *
     * @param userCss the source's custom CSS; may be blank
     */
    public static String buildInjectionScript(String userCss) {
        String user = userCss == null ? "" : userCss;
        return "(function(){"
                + "var set=function(id,css){"
                + "var d=document;if(!d.head&&!d.documentElement)return;"
                + "var e=d.getElementById(id);"
                + "if(!e){e=d.createElement('style');e.id=id;e.type='text/css';"
                + "(d.head||d.documentElement).appendChild(e);}"
                + "e.textContent=css;};"
                + "set('" + BASE_STYLE_ID + "'," + toJsString(TRANSPARENCY_BASE_CSS) + ");"
                + "set('" + USER_STYLE_ID + "'," + toJsString(user) + ");"
                + "})();";
    }

    /**
     * Escapes an arbitrary string into a JavaScript string literal.
     *
     * <p>User CSS is untrusted text that ends up inside a script we execute, so
     * quotes, backslashes, newlines and {@code </script>} sequences all have to
     * be neutralised. Non-ASCII is escaped too, because the JS is handed to CEF
     * as a plain string whose encoding we would rather not depend on.</p>
     */
    public static String toJsString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                // Closing an enclosing script/HTML context must be impossible.
                case '<' -> out.append("\\u003C");
                case '>' -> out.append("\\u003E");
                case '&' -> out.append("\\u0026");
                default -> {
                    // Also covers U+2028/U+2029, which are not legal raw
                    // characters inside a JavaScript string literal.
                    if (c < 0x20 || c > 0x7E) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Script that pushes the host clipboard into the page for the paste fallback. */
    public static String buildClipboardScript(String clipboardText) {
        return "window.__streamableClipboard=" + toJsString(clipboardText == null ? "" : clipboardText) + ";";
    }
}
