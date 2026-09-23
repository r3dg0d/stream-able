package dev.streamable.audio.dsp;

/** Test signal generation and measurement. */
final class Signals {

    static final int SR = MicrophoneChain.SAMPLE_RATE;

    private Signals() {
    }

    static float[] sine(double frequency, double amplitude, int samples) {
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (float) (amplitude * Math.sin(2 * Math.PI * frequency * i / SR));
        }
        return out;
    }

    static float[] constant(double value, int samples) {
        float[] out = new float[samples];
        java.util.Arrays.fill(out, (float) value);
        return out;
    }

    static float[] noise(double amplitude, int samples, long seed) {
        java.util.Random random = new java.util.Random(seed);
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (float) (amplitude * (random.nextDouble() * 2 - 1));
        }
        return out;
    }

    static double rms(float[] data, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += data[i] * (double) data[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    static double rmsDb(float[] data, int from, int to) {
        return Db.fromLinear(rms(data, from, to));
    }

    static double peak(float[] data) {
        double peak = 0;
        for (float v : data) {
            peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    /** Runs a stage over a signal in 480-sample blocks, like the DSP worker. */
    static float[] run(AudioStage stage, float[] input) {
        float[] data = input.clone();
        for (int i = 0; i < data.length; i += 480) {
            stage.process(data, i, Math.min(480, data.length - i));
        }
        return data;
    }

    static float[] concat(float[]... parts) {
        int length = 0;
        for (float[] part : parts) {
            length += part.length;
        }
        float[] out = new float[length];
        int offset = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
