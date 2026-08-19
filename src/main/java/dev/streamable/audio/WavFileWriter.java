package dev.streamable.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Writes a streaming WAV file for the recording's audio track.
 *
 * <p>The RIFF header carries two byte counts that are only known once capture
 * ends, so a placeholder header is written up front and patched on close. That
 * lets audio be written continuously during a long session without buffering it
 * in memory, and means a crash leaves a file whose header is wrong but whose
 * samples are intact - which the crash-recovery remux can still use.</p>
 */
public final class WavFileWriter implements AutoCloseable {

    private static final int HEADER_BYTES = 44;

    private final RandomAccessFile file;
    private final Path path;
    private long dataBytes;
    private boolean closed;

    public WavFileWriter(Path path, int sampleRate, int channels, int bitsPerSample) throws IOException {
        this.path = path;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        this.file.setLength(0);
        writePlaceholderHeader(sampleRate, channels, bitsPerSample);
    }

    private void writePlaceholderHeader(int sampleRate, int channels, int bitsPerSample) throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        file.writeBytes("RIFF");
        writeIntLe(0);                    // patched on close
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        writeIntLe(16);                   // PCM chunk size
        writeShortLe((short) 1);          // PCM format
        writeShortLe((short) channels);
        writeIntLe(sampleRate);
        writeIntLe(byteRate);
        writeShortLe((short) blockAlign);
        writeShortLe((short) bitsPerSample);
        file.writeBytes("data");
        writeIntLe(0);                    // patched on close
    }

    public synchronized void write(byte[] pcm, int offset, int length) throws IOException {
        if (closed || length <= 0) {
            return;
        }
        file.write(pcm, offset, length);
        dataBytes += length;
    }

    /** An {@link OutputStream} view, for capture code that writes to a stream. */
    public OutputStream asOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                WavFileWriter.this.write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                WavFileWriter.this.write(b, off, len);
            }
        };
    }

    public Path path() {
        return path;
    }

    public long dataBytes() {
        return dataBytes;
    }

    /** True when nothing beyond the header was written. */
    public boolean isEmpty() {
        return dataBytes <= 0;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            file.seek(4);
            writeIntLe((int) Math.min(Integer.MAX_VALUE, 36 + dataBytes));
            file.seek(40);
            writeIntLe((int) Math.min(Integer.MAX_VALUE, dataBytes));
        } finally {
            file.close();
        }
    }

    private void writeIntLe(int value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >> 8) & 0xFF);
        file.write((value >> 16) & 0xFF);
        file.write((value >> 24) & 0xFF);
    }

    private void writeShortLe(short value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >> 8) & 0xFF);
    }

    public static int headerBytes() {
        return HEADER_BYTES;
    }
}
