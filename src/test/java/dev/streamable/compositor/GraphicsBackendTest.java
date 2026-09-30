package dev.streamable.compositor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphicsBackendTest {

    @Test
    void reportsNoOpenGlWithoutAContextInsteadOfThrowing() {
        // A plain JVM has no GL context (and maybe no libGL at all): the check
        // must say "unavailable", never throw and never touch native GL.
        assertFalse(GraphicsBackend.openGlAvailable());
    }

    @Test
    void theMessageNamesTheSettingThatFixesIt() {
        String message = GraphicsBackend.UNSUPPORTED_MESSAGE;
        assertNotNull(message);
        assertTrue(message.contains("Graphics API"), message);
        assertTrue(message.contains("Prefer OpenGL"), message);
        assertTrue(message.contains("restart"), message);
    }

    @Test
    void noReasonIsReportedBeforeTheFirstFailedCheck() {
        // Only checkOpenGl() records a failure, so a fresh JVM has no reason to show.
        // (Other tests in this class must not call checkOpenGl().)
        assertNull(GraphicsBackend.unsupportedReason());
    }
}
