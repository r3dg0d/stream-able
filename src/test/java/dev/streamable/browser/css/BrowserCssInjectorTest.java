package dev.streamable.browser.css;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserCssInjectorTest {

    @Test
    void injectionScriptCarriesBothStyleBlocks() {
        String script = BrowserCssInjector.buildInjectionScript("body { color: red; }");
        assertTrue(script.contains(BrowserCssInjector.BASE_STYLE_ID));
        assertTrue(script.contains(BrowserCssInjector.USER_STYLE_ID));
        assertTrue(script.contains("background"), "forced transparency must be present");
        assertTrue(script.contains("window.__streamableCss"), "payload lives on window for SPA re-apply");
    }

    @Test
    void injectionScriptRetriesWhileLoadingAndGuardsSpaHeadClears() {
        String script = BrowserCssInjector.buildInjectionScript("");
        assertTrue(script.contains("DOMContentLoaded"), "must wait when document is still loading");
        assertTrue(script.contains("MutationObserver"), "must re-apply after SPA head rebuilds");
        assertTrue(script.contains("__streamableCssGuard"), "observer installs once per document");
    }

    @Test
    void transparencyBaseTargetsHtmlAndBodyWithImportant() {
        // A widget shipping "body { background: #000 }" must still composite.
        assertTrue(BrowserCssInjector.TRANSPARENCY_BASE_CSS.contains("html"));
        assertTrue(BrowserCssInjector.TRANSPARENCY_BASE_CSS.contains("body"));
        assertTrue(BrowserCssInjector.TRANSPARENCY_BASE_CSS.contains("!important"));
        assertTrue(BrowserCssInjector.TRANSPARENCY_BASE_CSS.contains("background-image"),
                "opaque wallpaper images must be cleared too");
    }

    @Test
    void escapesQuotesAndBackslashes() {
        assertEquals("\"a\\\"b\"", BrowserCssInjector.toJsString("a\"b"));
        assertEquals("\"a\\\\b\"", BrowserCssInjector.toJsString("a\\b"));
    }

    @Test
    void escapesNewlinesSoTheLiteralStaysOnOneLine() {
        String escaped = BrowserCssInjector.toJsString("a\nb\r\nc");
        assertFalse(escaped.contains("\n"), "raw newlines are illegal in a JS string literal");
        assertTrue(escaped.contains("\\n"));
    }

    @Test
    void neutralisesScriptTagBreakout() {
        // User CSS is untrusted text that ends up inside a script we execute.
        String escaped = BrowserCssInjector.toJsString("</script><script>alert(1)</script>");
        assertFalse(escaped.contains("</script>"));
        assertFalse(escaped.contains("<"));
        assertTrue(escaped.contains("\\u003C"));
    }

    @Test
    void escapesLineSeparatorsAndNonAscii() {
        String escaped = BrowserCssInjector.toJsString("café\u2028x");
        assertTrue(escaped.contains("\\u00E9"));
        assertTrue(escaped.contains("\\u2028"));
    }

    @Test
    void handlesEmptyAndNullCss() {
        assertNotNull(BrowserCssInjector.buildInjectionScript(null));
        assertNotNull(BrowserCssInjector.buildInjectionScript(""));
        assertEquals("", BrowserCssInjector.clampUserCss(null));
        assertEquals("", BrowserCssInjector.clampUserCss(""));
    }

    @Test
    void clampsOversizedUserCssBeforeInjection() {
        String huge = "a".repeat(BrowserCssInjector.MAX_USER_CSS_CHARS + 2_048);
        assertEquals(BrowserCssInjector.MAX_USER_CSS_CHARS, BrowserCssInjector.clampUserCss(huge).length());
        String script = BrowserCssInjector.buildInjectionScript(huge);
        // Each 'a' survives escaping unchanged; the user payload must be the
        // clamped run, never the oversized original (base CSS may add a few more).
        assertTrue(script.contains("a".repeat(BrowserCssInjector.MAX_USER_CSS_CHARS)));
        assertFalse(script.contains("a".repeat(BrowserCssInjector.MAX_USER_CSS_CHARS + 1)),
                "injection script must not embed unclamped CSS");
    }

    @Test
    void clipboardScriptEscapesItsPayload() {
        String script = BrowserCssInjector.buildClipboardScript("a\"b\nc");
        assertTrue(script.startsWith("window.__streamableClipboard="));
        assertFalse(script.contains("\n"));
    }
}
