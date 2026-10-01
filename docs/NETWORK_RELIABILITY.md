# Streaming through uneven connections

An encoder shared by several destinations used to write to every ingest
synchronously. A socket that stopped accepting data could hold up the encoder,
exhaust capture buffers, and slow every healthy destination. `onfail=ignore`
only helped after the write actually failed.

Multistream output now uses FFmpeg's own FIFO-backed tee implementation:

```
-use_fifo 1
-fifo_options queue_size=120:drop_pkts_on_overflow=1:restart_with_keyframe=1
```

Each destination has its own worker and bounded encoded-packet queue. Overflow
sacrifices packets on the congested output; resumption waits for a keyframe.
120 packets is approximately one second of 60 FPS video plus 48 kHz AAC, not a
fixed duration or byte budget. Socket buffers and a blocked write can add more
latency. The existing Java reconnect policy still handles reported failures;
FIFO does not introduce an independent recovery loop.

Single-destination output still uses direct FLV. This change isolates shared
outputs; it does not remedy an overloaded GPU, insufficient upstream capacity,
or a single stalled connection. It does not change saved profiles or keys.

## Reading Stream Health

The status card is now **Output**, because encoder FPS, input queue fullness and
destination errors cannot independently measure internet quality. FFmpeg's tee
progress does not provide usable output-byte totals. A dash means unavailable,
not zero throughput. Encoding progress also cannot prove that each asynchronous
destination has accepted every packet: Live is a coarse session indicator.

**Destination queue overflows** counts congestion episodes in the current
encoder process, not dropped frames or packets. A warning remains visible after
an overflow; reconnecting a process resets its counter. Failures with an ingest
URL still appear on that destination's row.

Every destination requires upload bandwidth even when it shares an encoder.
Four destinations at 8,000 kbps video plus 160 kbps audio need roughly
32,640 kbps upstream before protocol overhead and other traffic. If queues keep
overflowing, lower streaming bitrate or disable a slow destination. More queue
memory cannot create upload capacity.

## Research and verification

- [FFmpeg tee and FIFO documentation](https://www.ffmpeg.org/ffmpeg-formats.html#tee)
  describes output workers, overflow handling and keyframe restart. We use these
  existing open source muxers directly.
- [FFmpeg FIFO implementation](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/fifo.c)
  provides the packet queue and its `FIFO queue full` diagnostic.
- [OBS RTMP implementation](https://github.com/obsproject/obs-studio/blob/master/plugins/obs-outputs/rtmp-stream.c)
  implements dynamic bitrate from timed sends and runtime encoder updates.
  Stream-able's external FFmpeg process currently has no equivalent live encoder
  control interface. Restarting it to mimic adaptation would interrupt healthy
  destinations, so automatic bitrate adjustment is not claimed here.

`FFmpegTeeIsolationTest` exercises the production command with synthetic frames,
a healthy local FLV file and an owned loopback TCP receiver that accepts but
never reads. It requires FFmpeg on PATH. No service account or public broadcast
is involved. A separate FFmpeg 8.1.3 comparison at 8 Mbps / 60 FPS reached
44 frames after nine seconds with synchronous tee versus 450 with FIFO;
the healthy file grew from 524,288 to 8,015,542 bytes. This demonstrates stall
isolation, not performance on a particular public ingest or the user's ISP.
