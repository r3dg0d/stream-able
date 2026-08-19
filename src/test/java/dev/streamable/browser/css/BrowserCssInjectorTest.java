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
    }

    @Test
    void transparencyBaseTargetsHtmlAndBodyWithImportant() {
        // A widget shipping "body { background: #000 }" must still composite.
        assertTrue(BrowserCssInjector.TRANSPARENCY_BASE_CSS.contains("html"));
        assertTrue(BrowserCssInjector.TRANSPARENCY_BASE_CSS.contains("body"));
        assertTrue(BrowserCssInjector.TRANSPARENCY_BASE_CSS.contains("!important"));
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
        String escaped = BrowserCssInjector.toJsString("café x");
        assertTrue(escaped.contains("\\u00E9"));
        assertTrue(escaped.contains("\\u2028"));
    }

    @Test
    void handlesEmptyAndNullCss() {
        assertNotNull(BrowserCssInjector.buildInjectionScript(null));
        assertNotNull(BrowserCssInjector.buildInjectionScript(""));
    }

    @Test
    void clipboardScriptEscapesItsPayload() {
        String script = BrowserCssInjector.buildClipboardScript("a\"b\nc");
        assertTrue(script.startsWith("window.__streamableClipboard="));
        assertFalse(script.contains("\n"));
    }
}
