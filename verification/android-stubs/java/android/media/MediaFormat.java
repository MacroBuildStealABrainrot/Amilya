package android.media;

public class MediaFormat {
public static final String MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"; public static final String KEY_AAC_PROFILE = "aac-profile";
 public static final String KEY_BIT_RATE = "bitrate"; public static final String KEY_MAX_INPUT_SIZE = "max-input-size"; public static final String KEY_MIME = "mime";
 public static MediaFormat createAudioFormat(String mime, int rate, int channels) { return null; }
 public final void setInteger(String k, int v) {}
 public final int getInteger(String k) { return 0; }
 public final String getString(String k) { return null; }
}
