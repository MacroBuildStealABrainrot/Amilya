package android.media;

public final class MediaCodec {
public static final int CONFIGURE_FLAG_ENCODE = 1; public static final int BUFFER_FLAG_KEY_FRAME = 1; public static final int BUFFER_FLAG_END_OF_STREAM = 4; public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
 public static final class BufferInfo { public int offset; public int size; public long presentationTimeUs; public int flags; public void set(int o, int s, long t, int f) {} }
 public static MediaCodec createEncoderByType(String type) throws java.io.IOException { return null; }
 public void configure(MediaFormat f, android.view.Surface s, Object crypto, int flags) {}
 public final void start() {}
 public final void stop() {}
 public void release() {}
 public final int dequeueInputBuffer(long us) { return 0; }
 public final int dequeueOutputBuffer(BufferInfo info, long us) { return 0; }
 public java.nio.ByteBuffer getInputBuffer(int i) { return null; }
 public java.nio.ByteBuffer getOutputBuffer(int i) { return null; }
 public final void queueInputBuffer(int i, int o, int s, long t, int f) {}
 public final void releaseOutputBuffer(int i, boolean render) {}
}
