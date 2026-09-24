package dev.streamable.streaming.test;

/**
 * Synthetic test content: moving colour gradients (so the encoder has real
 * motion and detail to work on) and a quiet 440 Hz tone.
 */
final class TestPattern {

    private TestPattern() {
    }

    static byte[][] frames(int width, int height, int count) {
        byte[][] frames = new byte[count][];
        java.util.Random random = new java.util.Random(7);
        for (int f = 0; f < count; f++) {
            byte[] data = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 3)];
            int shift = f * width / (count * 3);
            for (int y = 0; y < height; y++) {
                int row = y * width * 3;
                for (int x = 0; x < width; x++) {
                    int i = row + x * 3;
                    int noise = random.nextInt(24);
                    data[i] = (byte) (((x + shift) * 255 / width) ^ noise);
                    data[i + 1] = (byte) ((y * 255 / height) + noise);
                    data[i + 2] = (byte) (((x + y + shift * 2) / 4) & 0xFF);
                }
            }
            frames[f] = data;
        }
        return frames;
    }

    /** 20 ms of stereo s16le 440 Hz at -20 dBFS. */
    static byte[] toneBlock(int sampleRate) {
        int frames = sampleRate / 50;
        byte[] pcm = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short v = (short) (3277 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
            pcm[4 * i] = (byte) v;
            pcm[4 * i + 1] = (byte) (v >> 8);
            pcm[4 * i + 2] = (byte) v;
            pcm[4 * i + 3] = (byte) (v >> 8);
        }
        return pcm;
    }
}
