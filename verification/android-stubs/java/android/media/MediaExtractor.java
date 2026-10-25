package android.media;

public class MediaExtractor {
public static final int SAMPLE_FLAG_SYNC = 1;
 public void setDataSource(String p) throws java.io.IOException {}
 public void setDataSource(MediaDataSource s) throws java.io.IOException {}
 public int getTrackCount() { return 0; }
 public MediaFormat getTrackFormat(int i) { return null; }
 public void selectTrack(int i) {}
 public int readSampleData(java.nio.ByteBuffer b, int o) { return -1; }
 public long getSampleTime() { return 0; }
 public int getSampleFlags() { return 0; }
 public int getSampleTrackIndex() { return -1; }
 public boolean advance() { return false; }
 public void release() {}
}
