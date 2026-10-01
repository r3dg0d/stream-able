package dev.streamable.streaming;

import dev.streamable.ffmpeg.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class UploadBudgetTest {
    private static DestinationGrouping.Group group(int bitrate, int count) {
        return new DestinationGrouping.Group(new EncodeProfile(
                VideoProfile.liveDefault(VideoEncoder.X264, 1280, 720, 60, bitrate), AudioProfile.LIVE_DEFAULT),
                IntStream.range(0, count).mapToObj(i -> new StreamDestination(UUID.randomUUID(), "Local " + i,
                        StreamPlatform.CUSTOM, new StreamingCredentials("rtmp://local/app", "fixture"))).toList());
    }
    @Test void fourOutputsFitMeasuredUploadWithHeadroom() {
        var original = group(8000, 4);
        var plan = UploadBudget.plan(List.of(original), 22.47, true);
        assertTrue(plan.reduced());
        assertEquals(4334, plan.groups().getFirst().profile().video().bitrateKbps());
        assertEquals(17976, plan.effectiveKbps());
        assertEquals(8000, original.profile().video().bitrateKbps(), "do not mutate saved preferences");
    }
    @Test void fewerDestinationsCanUseRequestedQuality() {
        var plan = UploadBudget.plan(List.of(group(8000, 2)), 22.47, true);
        assertFalse(plan.reduced());
        assertEquals(8000, plan.groups().getFirst().profile().video().bitrateKbps());
    }
    @Test void unknownUploadDoesNotGuessCapacity() {
        assertFalse(UploadBudget.plan(List.of(group(8000, 4)), 0, true).reduced());
        assertFalse(UploadBudget.plan(List.of(group(8000, 4)), Double.NaN, true).reduced());
    }
    @Test void incompatibleProfilesAndAudioAreAllBudgeted() {
        var plan = UploadBudget.plan(List.of(group(8000, 3), group(14000, 1)), 10, true);
        assertTrue(plan.effectiveKbps() <= 8000);
        assertEquals(2, plan.groups().size());
        assertTrue(plan.groups().get(1).profile().video().bitrateKbps()
                > plan.groups().getFirst().profile().video().bitrateKbps());
    }
    @Test void rejectsBudgetBelowMinimumAudioAndVideo() {
        assertThrows(IllegalArgumentException.class, () -> UploadBudget.plan(List.of(group(8000, 4)), 1, true));
    }
    @Test void mutedSessionUsesItsVideoBudget() {
        var plan = UploadBudget.plan(List.of(group(8000, 4)), 22.47, false);
        assertEquals(4494, plan.groups().getFirst().profile().video().bitrateKbps());
        assertEquals(17976, plan.effectiveKbps());
    }
    @Test void vbrCeilingsStayInsideTheBudget() {
        var g = group(6000, 4);
        var v = g.profile().video();
        var high = new VideoProfile(v.encoder(), v.width(), v.height(), v.fps(), RateControl.VBR,
                6000, 12000, 24000, 2, v.preset(), v.h264Profile(), 0);
        var plan = UploadBudget.plan(List.of(new DestinationGrouping.Group(new EncodeProfile(high,
                AudioProfile.LIVE_DEFAULT), g.destinations())), 22.47, true);
        assertTrue(plan.effectiveKbps() <= 17976);
        assertTrue(plan.groups().getFirst().profile().video().bitrateKbps()
                <= plan.groups().getFirst().profile().video().maxBitrateKbps());
        assertEquals(RateControl.VBR, plan.groups().getFirst().profile().video().rateControl());
    }
    @Test void unlimitedQualityCannotPromiseAnUploadCeiling() {
        var g = group(8000, 1);
        var v = g.profile().video();
        var q = new VideoProfile(v.encoder(), v.width(), v.height(), v.fps(), RateControl.CONSTANT_QUALITY,
                8000, 8000, 16000, 2, v.preset(), v.h264Profile(), 0);
        assertThrows(IllegalArgumentException.class, () -> UploadBudget.plan(List.of(new DestinationGrouping.Group(
                new EncodeProfile(q, AudioProfile.LIVE_DEFAULT), g.destinations())), 100, true));
    }
    @Test void emptyPlanIsSafe() {
        assertEquals(0, UploadBudget.plan(List.of(), 22.47, true).effectiveKbps());
    }
}
