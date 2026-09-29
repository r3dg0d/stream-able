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
 * their previous element rather than stacking up. The injection script also
 * waits for {@code DOMContentLoaded} when the document is still loading, and
 * installs a one-shot {@code MutationObserver} that re-applies the blocks if a
 * SPA clears {@code <head>} (widgets that rebuild the document tree used to go
 * opaque until the next full navigation).</p>
 *
 * <p>User CSS is clamped to {@link #MAX_USER_CSS_CHARS} before it is escaped
 * into the script, matching the size discipline of the browser audio tap: a
 * saved config must not hand CEF a multi-megabyte {@code executeJavaScript}.</p>
 */
public final class BrowserCssInjector {

    /** Element id for the forced-transparency block. */
    public static final String BASE_STYLE_ID = "streamable-transparency";
    /** Element id for the user's own CSS. */
    public static final String USER_STYLE_ID = "streamable-user-css";

    /**
     * Hard cap on user CSS characters (UTF-16 Java length). Large enough for
     * real overlay stylesheets; small enough that the escaped JS stays cheap
     * to ship across the CEF boundary on every load.
     */
    public static final int MAX_USER_CSS_CHARS = 65_536;

    /**
     * Forced transparency. {@code html} is included as well as {@code body}
     * because a page that only clears {@code body} still shows the root
     * element's default white. {@code background-image} is cleared too: many
     * widgets paint an opaque wallpaper via {@code url(...)} rather than a
     * solid colour.
     */
    public static final String TRANSPARENCY_BASE_CSS = """
            html, body {
                background: transparent !important;
                background-color: rgba(0, 0, 0, 0) !important;
                background-image: none !important;
            }
            """;

    private BrowserCssInjector() {
    }

    /**
     * Clamps user CSS to {@link #MAX_USER_CSS_CHARS}. Null becomes empty.
     * Truncation is silent at the storage boundary; Studio can surface length
     * separately if an editor is added later.
     */
    public static String clampUserCss(String userCss) {
        if (userCss == null || userCss.isEmpty()) {
            return "";
        }
        if (userCss.length() <= MAX_USER_CSS_CHARS) {
            return userCss;
        }
        return userCss.substring(0, MAX_USER_CSS_CHARS);
    }

    /**
     * Builds a self-contained script that installs both style blocks and keeps
     * them alive across SPA head rebuilds.
     *
     * @param userCss the source's custom CSS; may be blank; oversized input is clamped
     */
    public static String buildInjectionScript(String userCss) {
        String user = clampUserCss(userCss);
        // CSS payloads live on window.__streamableCss so a later applyCss()
        // updates what the MutationObserver re-installs (the observer itself
        // is installed only once per document).
        return "(function(){"
                + "var BASE='" + BASE_STYLE_ID + "';"
                + "var USER='" + USER_STYLE_ID + "';"
                + "window.__streamableCss={base:" + toJsString(TRANSPARENCY_BASE_CSS)
                + ",user:" + toJsString(user) + "};"
                + "var set=function(id,css){"
                + "var d=document;if(!d.documentElement)return false;"
                + "var parent=d.head||d.documentElement;"
                + "var e=d.getElementById(id);"
                + "if(!e){e=d.createElement('style');e.id=id;e.type='text/css';parent.appendChild(e);}"
                + "else if(e.parentNode!==parent){parent.appendChild(e);}"
                + "if(e.textContent!==css)e.textContent=css;"
                + "return true;};"
                + "var apply=function(){"
                + "var c=window.__streamableCss||{};"
                + "set(BASE,c.base||'');"
                + "set(USER,c.user||'');"
                + "};"
                + "apply();"
                + "if(document.readyState==='loading'){"
                + "document.addEventListener('DOMContentLoaded',apply,{once:true});"
                + "}"
                + "if(!window.__streamableCssGuard){"
                + "window.__streamableCssGuard=1;"
                + "try{"
                + "var mo=new MutationObserver(function(){"
                + "if(!document.getElementById(BASE)||!document.getElementById(USER))apply();"
                + "});"
                + "mo.observe(document.documentElement,{childList:true,subtree:true});"
                + "}catch(e){}"
                + "}"
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
