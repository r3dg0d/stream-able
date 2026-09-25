package dev.streamable.compat.voicechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleVoiceChatSupportTest {

    @Test
    void distanceGainFadesLinearlyToSilenceAtTheVoiceRange() {
        assertEquals(1f, SimpleVoiceChatSupport.distanceGain(0, 48), 1e-6);
        assertEquals(0.5f, SimpleVoiceChatSupport.distanceGain(24, 48), 1e-6);
        assertEquals(0f, SimpleVoiceChatSupport.distanceGain(60, 48), 1e-6);
    }

    @Test
    void unknownRangeOrDistanceDoesNotGuess() {
        assertEquals(1f, SimpleVoiceChatSupport.distanceGain(10, 0), 1e-6);
        assertEquals(1f, SimpleVoiceChatSupport.distanceGain(Double.NaN, 48), 1e-6);
    }

    @Test
    void scalingRoundsAndClamps() {
        assertArrayEquals(new short[]{500, -500, 16384},
                SimpleVoiceChatSupport.scale(new short[]{1000, -1000, Short.MAX_VALUE}, 0.5f));
        assertArrayEquals(new short[]{Short.MAX_VALUE, Short.MIN_VALUE},
                SimpleVoiceChatSupport.scale(new short[]{30000, -30000}, 2f));
    }
}
