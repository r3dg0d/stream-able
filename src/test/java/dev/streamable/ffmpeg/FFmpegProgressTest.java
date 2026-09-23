package dev.streamable.ffmpeg;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFmpegProgressTest {

    @Test
    void parsesAProgressBlock() {
        FFmpegProgress.Parser parser = new FFmpegProgress.Parser();
        FFmpegProgress block = null;
        for (String line : List.of("frame=600", "fps=59.94", "stream_0_0_q=23.0", "bitrate=6012.4kbits/s",
                "total_size=7515500", "out_time_us=10000000", "dup_frames=2", "drop_frames=1",
                "speed=1.01x", "progress=continue")) {
            FFmpegProgress result = parser.accept(line);
            if (result != null) {
                block = result;
            }
        }
        assertEquals(600, block.frame());
        assertEquals(59.94, block.fps(), 1e-9);
        assertEquals(6012.4, block.bitrateKbps(), 1e-9);
        assertEquals(7_515_500, block.totalSize());
        assertEquals(2, block.duplicated());
        assertEquals(1, block.dropped());
        assertEquals(1.01, block.speed(), 1e-9);
        assertFalse(block.ended());
        assertNull(parser.accept("frame=601"), "only progress= completes a block");
        assertTrue(parser.accept("progress=end").ended());
    }

    @Test
    void unknownValuesDoNotBreakParsing() {
        FFmpegProgress.Parser parser = new FFmpegProgress.Parser();
        parser.accept("bitrate=N/A");
        parser.accept("speed=N/A");
        parser.accept("garbage");
        FFmpegProgress block = parser.accept("progress=continue");
        assertEquals(-1, block.bitrateKbps());
        assertEquals(-1, block.speed());
        assertEquals(2500.0, FFmpegProgress.Parser.parseBitrate("2.5mbits/s"));
    }

    @Test
    void driverDirectoryIsAppendedOnLinuxOnly() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("drivers");
        Map<String, String> env = new HashMap<>();
        env.put("LD_LIBRARY_PATH", "/custom/lib");
        FFmpegProcesses.applyEnvironment(env, "Linux", List.of(dir.toString(), "/definitely/not/here"));
        assertEquals("/custom/lib" + java.io.File.pathSeparator + dir, env.get("LD_LIBRARY_PATH"));

        Map<String, String> windows = new HashMap<>();
        FFmpegProcesses.applyEnvironment(windows, "Windows 11", List.of(dir.toString()));
        assertTrue(windows.isEmpty());
    }

    @Test
    void probeCommandsUseTheRealPipelinePlumbing() {
        List<String> nvenc = FFmpegCapabilityProbe.probeCommand("ffmpeg", VideoEncoder.NVENC_H264);
        assertTrue(nvenc.contains("testsrc2=s=1280x720:r=60"));
        assertTrue(nvenc.contains("h264_nvenc"));
        List<String> vaapi = FFmpegCapabilityProbe.probeCommand("ffmpeg", VideoEncoder.VAAPI_H264);
        assertTrue(vaapi.contains("-vaapi_device"));
        assertTrue(vaapi.get(vaapi.indexOf("-vf") + 1).endsWith("hwupload"));
    }

    @Test
    void explainsCommonHardwareFailures() {
        assertTrue(FFmpegCapabilityProbe.explain("[h264_nvenc] Driver does not support the required nvenc API version. "
                + "The minimum required Nvidia driver for nvenc is 610.00").contains("too old"));
        assertTrue(FFmpegCapabilityProbe.explain("Cannot load libcuda.so.1").contains("NVIDIA"));
        assertTrue(FFmpegCapabilityProbe.explain("libva-drm.so.2: cannot open").contains("VA-API"));
    }
}
