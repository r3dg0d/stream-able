package dev.streamable.audio;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans the single OpenAL loopback capture out to several consumers.
 *
 * <p>{@link OpenALLoopbackCapture} exposes exactly one recording stream slot.
 * That is fine when only one thing wants game audio, but Stream-able routinely
 * has two: a recording writing a WAV file and a broadcast feeding the live
 * mixer. Pointing the slot at whichever started last would silently cut the
 * other one's audio - a recording made while streaming would come out mute.</p>
 *
 * <p>This class owns the slot instead and forwards every block to every
 * registered consumer, so recording and streaming stay genuinely independent:
 * either can start or stop without affecting the other's audio.</p>
 *
 * <p>Consumers are held in a copy-on-write list because writes happen on the
 * OpenAL render thread while registration happens on the client thread, and the
 * audio path must never block on a lock.</p>
 */
public final class GameAudioTap {

    private static final GameAudioTap INSTANCE = new GameAudioTap();

    private final CopyOnWriteArrayList<OutputStream> consumers = new CopyOnWriteArrayList<>();
    private volatile boolean attached;

    private GameAudioTap() {
    }

    public static GameAudioTap getInstance() {
        return INSTANCE;
    }

    /** Starts receiving game audio and forwards it to {@code consumer}. */
    public synchronized void addConsumer(OutputStream consumer) {
        if (consumer == null) {
            return;
        }
        consumers.addIfAbsent(consumer);
        attach();
    }

    /**
     * Stops forwarding to {@code consumer}, detaching from the capture entirely
     * once nothing is listening.
     */
    public synchronized void removeConsumer(OutputStream consumer) {
        consumers.remove(consumer);
        if (consumers.isEmpty()) {
            detach();
        }
    }

    public int consumerCount() {
        return consumers.size();
    }

    private void attach() {
        if (attached) {
            return;
        }
        OpenALLoopbackCapture.getInstance().setRecordingStream(new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] data, int offset, int length) {
                for (OutputStream consumer : consumers) {
                    try {
                        consumer.write(data, offset, length);
                    } catch (IOException | RuntimeException e) {
                        // One failing sink must not silence the others.
                        StreamAbleLog.AUDIO.warn("Dropping a game-audio consumer after an error", e);
                        consumers.remove(consumer);
                    }
                }
            }
        });
        attached = true;
    }

    private void detach() {
        if (!attached) {
            return;
        }
        OpenALLoopbackCapture.getInstance().setRecordingStream(null);
        attached = false;
    }
}
