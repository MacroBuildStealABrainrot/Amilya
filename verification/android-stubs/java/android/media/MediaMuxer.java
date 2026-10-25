package android.media;

public class MediaMuxer {
public static final class OutputFormat { public static final int MUXER_OUTPUT_MPEG_4 = 0; }
 public MediaMuxer(String path, int format) throws java.io.IOException {}
 public int addTrack(MediaFormat f) { return 0; }
 public void start() {}
 public void stop() {}
 public void release() {}
 public void writeSampleData(int t, java.nio.ByteBuffer b, MediaCodec.BufferInfo i) {}
}
