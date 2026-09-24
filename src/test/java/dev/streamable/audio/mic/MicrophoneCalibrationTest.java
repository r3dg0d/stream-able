package dev.streamable.audio.mic;

import dev.streamable.audio.dsp.Db;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrophoneCalibrationTest {

    private static double power(double db) {
        return Math.pow(10, db / 10);
    }

    @Test
    void quietMicGetsGainTowardsTheTarget() {
        var result = MicrophoneCalibration.analyse(power(-75), power(-32), Db.toLinear(-18), Db.toLinear(-12), 0, 240_000);
        assertEquals(8, result.recommendedInputGainDb(), 0.01, "limited by loud peaks: -12 + 8 = -4 dBFS");
        assertFalse(result.upstreamClipping());
        assertTrue(result.recommendedGateDb() > -75 + 8 - 1 && result.recommendedGateDb() < -32 + 8 - 14);
    }

    @Test
    void clippingBeforeStreamAbleIsReportedHonestly() {
        var result = MicrophoneCalibration.analyse(power(-60), power(-10), 1.0, 1.0, 2_000, 240_000);
        assertTrue(result.upstreamClipping());
        assertTrue(result.advice().stream().anyMatch(a ->
                a.equals("Input is already clipping before Stream-able processing. Lower the microphone/interface gain.")));
        assertTrue(result.recommendedInputGainDb() <= 0, "digital gain cannot fix upstream clipping");
    }

    @Test
    void noisyRoomSuggestsNoiseCancellation() {
        var result = MicrophoneCalibration.analyse(power(-35), power(-25), Db.toLinear(-10), Db.toLinear(-8), 0, 240_000);
        assertTrue(result.advice().stream().anyMatch(a -> a.contains("AI noise cancellation")));
    }

    @Test
    void silenceIsDetected() {
        var result = MicrophoneCalibration.analyse(power(-90), power(-85), Db.toLinear(-80), Db.toLinear(-80), 0, 240_000);
        assertTrue(result.advice().getFirst().contains("No speech"));
        assertEquals(0, result.recommendedInputGainDb());
    }
}
