package dev.streamable.video;

import dev.streamable.ffmpeg.VideoEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputTransformTest {

    private static final double EPS = 1e-6;
    private static final Resolution UW = new Resolution(3440, 1440);
    private static final Resolution SUW = new Resolution(5120, 1440);
    private static final Resolution FHD = new Resolution(1920, 1080);

    private static OutputTransform t(Resolution src, Resolution dst, ScalingMode mode) {
        return OutputTransform.compute(src, dst, mode);
    }

    @ParameterizedTest
    @CsvSource({
            "1920,1080,WIDESCREEN,16:9", "2560,1440,WIDESCREEN,16:9", "3840,2160,WIDESCREEN,16:9",
            "1280,720,WIDESCREEN,16:9", "1920,1200,WIDESCREEN,16:10", "2560,1600,WIDESCREEN,16:10",
            "2560,1080,ULTRAWIDE,21:9", "3440,1440,ULTRAWIDE,21:9", "3840,1600,ULTRAWIDE,21:9",
            "3840,1080,SUPER_ULTRAWIDE,32:9", "5120,1440,SUPER_ULTRAWIDE,32:9",
            "1024,768,STANDARD,4:3", "1080,1920,PORTRAIT,9:16", "2000,1000,CUSTOM,2:1"
    })
    void classifiesAspectWithTolerance(int w, int h, AspectClass expected, String marketed) {
        Resolution resolution = new Resolution(w, h);
        assertEquals(expected, resolution.aspectClass());
        assertEquals(marketed, resolution.marketedRatio());
    }

    @Test
    void ultrawideIsNotMathematically21by9() {
        assertEquals("43:18", UW.reducedRatio());
        assertTrue(UW.isUltrawide());
        assertFalse(UW.isSuperUltrawide());
        assertTrue(SUW.isSuperUltrawide());
        assertEquals(4_953_600L, UW.pixelCount());
    }

    @Test
    void identity() {
        OutputTransform native_ = t(UW, UW, ScalingMode.NATIVE);
        assertTrue(native_.isIdentity());
        assertFalse(native_.resamples());
        for (ScalingMode mode : ScalingMode.values()) {
            assertTrue(t(UW, UW, mode).isIdentity(), mode.name());
        }
    }

    @Test
    void fitLetterboxesUltrawideInto16by9() {
        OutputTransform fit = t(UW, FHD, ScalingMode.FIT);
        assertEquals(0, fit.srcX(), EPS);
        assertEquals(3440, fit.srcW(), EPS);
        assertEquals(1920, fit.dstW(), EPS);
        assertEquals(1920 * 1440 / 3440.0, fit.dstH(), EPS);
        assertEquals((1080 - fit.dstH()) / 2, fit.dstY(), EPS);
        assertTrue(fit.isLetterboxed());
        assertFalse(fit.isPillarboxed());
        assertFalse(fit.cropsSource());
        assertFalse(fit.distorts());
    }

    @Test
    void fitPillarboxesNarrowIntoWide() {
        OutputTransform fit = t(FHD, UW, ScalingMode.FIT);
        assertTrue(fit.isPillarboxed());
        assertEquals(1440, fit.dstH(), EPS);
        assertEquals(2560, fit.dstW(), EPS);
        assertEquals(440, fit.dstX(), EPS);
    }

    @Test
    void fillCropsSidesOfUltrawide() {
        OutputTransform fill = t(UW, FHD, ScalingMode.FILL);
        assertFalse(fill.hasBars());
        assertTrue(fill.cropsSource());
        assertEquals(2560, fill.srcW(), EPS);
        assertEquals(1440, fill.srcH(), EPS);
        assertEquals(440, fill.srcX(), EPS);
        assertEquals(0, fill.srcY(), EPS);
        assertFalse(fill.distorts());
        assertEquals(0.75, fill.scaleX(), EPS);
    }

    @Test
    void centerCropTakesTheCentralRegionAtNativeSharpness() {
        OutputTransform crop = t(UW, FHD, ScalingMode.CENTER_CROP);
        assertEquals(760, crop.srcX(), EPS);
        assertEquals(180, crop.srcY(), EPS);
        assertEquals(1920, crop.srcW(), EPS);
        assertEquals(1080, crop.srcH(), EPS);
        assertFalse(crop.resamples());
        assertFalse(crop.hasBars());
        assertTrue(crop.describe().contains("full sharpness"), crop.describe());
    }

    @Test
    void centerCropFallsBackToFillWhenSourceIsSmaller() {
        OutputTransform crop = t(new Resolution(1280, 720), UW, ScalingMode.CENTER_CROP);
        OutputTransform fill = t(new Resolution(1280, 720), UW, ScalingMode.FILL);
        assertEquals(fill.srcX(), crop.srcX(), EPS);
        assertEquals(fill.srcW(), crop.srcW(), EPS);
        assertFalse(crop.hasBars());
    }

    @Test
    void stretchIsTheOnlyModeThatDistorts() {
        assertTrue(t(UW, FHD, ScalingMode.STRETCH).distorts());
        for (ScalingMode mode : List.of(ScalingMode.NATIVE, ScalingMode.FIT, ScalingMode.FILL, ScalingMode.CENTER_CROP)) {
            for (Resolution out : List.of(FHD, new Resolution(2560, 1080), new Resolution(3840, 1080), SUW)) {
                assertFalse(t(UW, out, mode).distorts(), mode + " " + out);
                assertFalse(t(SUW, out, mode).distorts(), mode + " " + out);
            }
        }
    }

    @Test
    void ultrawideToOtherUltrawide() {
        OutputTransform fit = t(UW, new Resolution(2560, 1080), ScalingMode.FIT);
        // 43:18 vs 64:27 are close but not equal: tiny bars, no distortion.
        assertFalse(fit.distorts());
        assertTrue(fit.dstW() <= 2560 + EPS && fit.dstH() <= 1080 + EPS);
    }

    @Test
    void superUltrawideConversions() {
        OutputTransform to1080 = t(SUW, FHD, ScalingMode.FIT);
        assertEquals(1920, to1080.dstW(), EPS);
        assertEquals(540, to1080.dstH(), EPS);
        assertEquals(270, to1080.dstY(), EPS);

        OutputTransform half = t(SUW, new Resolution(3840, 1080), ScalingMode.FIT);
        assertFalse(half.hasBars(), "same 32:9 shape scales exactly");
        assertEquals(0.75, half.scaleX(), EPS);

        OutputTransform crop = t(SUW, FHD, ScalingMode.FILL);
        assertEquals(2560, crop.srcW(), EPS);
        assertEquals(1280, crop.srcX(), EPS);
    }

    @Test
    void nativeCentresOneToOne() {
        OutputTransform smaller = t(UW, FHD, ScalingMode.NATIVE);
        assertFalse(smaller.resamples());
        assertEquals(760, smaller.srcX(), EPS);
        OutputTransform larger = t(FHD, UW, ScalingMode.NATIVE);
        assertFalse(larger.resamples());
        assertEquals(760, larger.dstX(), EPS);
        assertEquals(180, larger.dstY(), EPS);
    }

    @Test
    void pointMappingRoundTrips() {
        for (ScalingMode mode : ScalingMode.values()) {
            OutputTransform transform = t(UW, FHD, mode);
            double[] out = transform.sourceToOutput(3000, 700);
            double[] back = transform.outputToSource(out[0], out[1]);
            assertEquals(3000, back[0], 1e-6, mode.name());
            assertEquals(700, back[1], 1e-6, mode.name());
        }
    }

    @Test
    void uvCoordinatesMatchTheCropRectangle() {
        float[] uv = t(UW, FHD, ScalingMode.FILL).sourceUv();
        assertEquals(440f / 3440f, uv[0], 1e-6);
        assertEquals(0f, uv[1], 1e-6);
        assertEquals(3000f / 3440f, uv[2], 1e-6);
        assertEquals(1f, uv[3], 1e-6);
    }

    @Test
    void resolutionLimitsAndOverflowSafety() {
        assertThrows(IllegalArgumentException.class, () -> new Resolution(0, 1080));
        assertThrows(IllegalArgumentException.class, () -> new Resolution(20000, 1080));
        assertThrows(IllegalArgumentException.class, () -> new Resolution(16384, 16384));
        assertNull(Resolution.tryOf(-1, 5));
        Resolution big = new Resolution(8192, 8192);
        assertEquals(8192L * 8192 * 4, big.frameBytes(4));
        assertEquals(8192 * 8192 * 3, big.frameBytesInt(3));
        assertEquals(new Resolution(5120, 1440), Resolution.parse("5120 × 1440"));
        Resolution clamped = Resolution.clamped(100_000, 3);
        assertTrue(clamped.width() <= Resolution.MAX_DIMENSION && clamped.height() >= Resolution.MIN_DIMENSION);
    }

    @Test
    void oddDimensionsAreReportedNotSilentlyChanged() {
        Resolution odd = new Resolution(3441, 1441);
        assertEquals(3441, odd.width());
        var issues = OutputValidation.validate(odd, 60, VideoEncoder.X264, OutputValidation.Target.RECORDING);
        assertTrue(OutputValidation.hasErrors(issues));
        assertTrue(OutputValidation.firstError(issues).contains("3440 × 1440"), OutputValidation.firstError(issues));
    }

    @Test
    void hardwareH264WidthLimit() {
        var nvenc = OutputValidation.validate(SUW, 60, VideoEncoder.NVENC_H264, OutputValidation.Target.RECORDING);
        assertTrue(OutputValidation.hasErrors(nvenc), "NVENC H.264 cannot encode 5120 wide");
        var hevc = OutputValidation.validate(SUW, 60, VideoEncoder.NVENC_HEVC, OutputValidation.Target.RECORDING);
        assertFalse(OutputValidation.hasErrors(hevc));
        var x264 = OutputValidation.validate(SUW, 60, VideoEncoder.X264, OutputValidation.Target.RECORDING);
        assertFalse(OutputValidation.hasErrors(x264));
    }

    @Test
    void ultrawideStreamsGetASoftWarningOnly() {
        var issues = OutputValidation.validate(UW, 60, VideoEncoder.X264, OutputValidation.Target.STREAMING);
        assertFalse(OutputValidation.hasErrors(issues));
        assertTrue(issues.stream().anyMatch(i -> i.severity() == OutputValidation.Severity.WARNING
                && i.message().contains("ultrawide")));
        assertTrue(OutputValidation.validate(FHD, 60, VideoEncoder.X264, OutputValidation.Target.STREAMING).isEmpty());
    }

    @Test
    void suggestedStreamOutputs() {
        assertEquals(new Resolution(2560, 1440), ResolutionPresets.suggestedStreamOutput(UW));
        assertEquals(new Resolution(2560, 1440), ResolutionPresets.suggestedStreamOutput(SUW));
        assertEquals(FHD, ResolutionPresets.suggestedStreamOutput(new Resolution(2560, 1080)));
        assertEquals(FHD, ResolutionPresets.suggestedStreamOutput(FHD));
    }
}
