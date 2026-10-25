package android.media;

public class AudioRecord {
public static final int STATE_INITIALIZED = 1;
 public android.media.AudioRecordingConfiguration getActiveRecordingConfiguration() { return null; }
 public AudioRecord(int source, int rate, int channelConfig, int format, int bufferBytes) {}
 public static int getMinBufferSize(int rate, int channelConfig, int format) { return 0; }
 public int getState() { return 1; }
 public void startRecording() {}
 public void stop() {}
 public void release() {}
 public int read(byte[] data, int offset, int size) { return 0; }
}
