package dev.streamable.streaming.test;

import java.util.List;

/**
 * Results and live numbers of a destination test.
 *
 * @param targetKbps          configured video + audio bitrate
 * @param achievedKbps        current measured output bitrate (FFmpeg total_size delta)
 * @param averageKbps         average output bitrate over the test so far
 * @param encoderFps          FFmpeg's reported encode frame rate
 * @param targetFps           configured frame rate
 * @param encodeLatencyMillis average time from frame written to frame encoded
 * @param speed               encode speed relative to real time
 * @param droppedFrames       frames our queue had to drop (encoder or network too slow)
 * @param maxQueuePressure    highest queue fill seen, 0..1
 * @param reconnects          reconnects during the test (the test does not reconnect: always 0 or 1 failure)
 * @param elapsedSeconds      test duration so far
 * @param ffmpegStatus        FFmpeg state, already redacted
 * @param networkChecks       DNS/TCP/TLS/RTMP results
 * @param findings            plain-language interpretation
 */
public record StreamTestMetrics(int targetKbps, double achievedKbps, double averageKbps, double encoderFps, int targetFps,
                                double encodeLatencyMillis, double speed, long droppedFrames, double maxQueuePressure,
                                int reconnects, double elapsedSeconds, String ffmpegStatus,
                                List<NetworkProbe.Check> networkChecks, List<String> findings) {

    public static final StreamTestMetrics EMPTY = new StreamTestMetrics(0, -1, -1, -1, 0, -1, -1, 0, 0, 0, 0, "",
            List.of(), List.of());
}
