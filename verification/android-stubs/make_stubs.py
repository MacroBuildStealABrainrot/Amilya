"""Writes the stand-in Java declarations (one file per class) under ./java. Names/argument lists follow the real Android SDK as
remembered by the author; they are NOT the SDK. Run:  python3 -I make_stubs.py"""
import os, textwrap
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "java")
S = {}
def c(fq, body, kind="public class", imports=""):
    S[fq] = (kind, body, imports)

c("android.R", "public static final class drawable { public static final int ic_btn_speak_now = 17301620; public static final int ic_dialog_info = 17301659; }")
c("android.Manifest", "public static final class permission { public static final String RECORD_AUDIO = \"android.permission.RECORD_AUDIO\"; }")
c("android.os.Looper", "public static Looper getMainLooper() { return null; }")
c("android.os.Handler", """public Handler(Looper l) {}
 public final boolean post(Runnable r) { return true; }
 public final boolean postDelayed(Runnable r, long d) { return true; }
 public final void removeCallbacks(Runnable r) {}
 public final void removeCallbacksAndMessages(Object token) {}""")
c("android.os.IBinder", "", "public interface")
c("android.os.SystemClock", "public static long elapsedRealtime() { return 0; }")
c("android.os.Build", "public static class VERSION { public static final int SDK_INT = 34; }\n public static class VERSION_CODES { public static final int R = 30; public static final int S = 31; public static final int O = 26; public static final int P = 28; public static final int Q = 29; }")
c("android.os.StatFs", "public StatFs(String path) {}\n public long getAvailableBytes() { return 0; }\n public long getTotalBytes() { return 0; }")
c("android.content.ComponentName", "public ComponentName(Context c, Class<?> cls) {}")
c("android.content.Intent", """public Intent() {}
 public Intent(Context c, Class<?> cls) {}
 public String getAction() { return null; }
 public Intent setAction(String a) { return this; }
 public Intent putExtra(String n, String v) { return this; }
 public String getStringExtra(String n) { return null; }""")
c("android.content.Context", """public abstract java.io.File getFilesDir();
 public abstract java.io.File getNoBackupFilesDir();
 public abstract java.io.File getCacheDir();
 public abstract android.content.pm.PackageManager getPackageManager();
 public abstract String getPackageName();
 public abstract Context getApplicationContext();
 public abstract <T> T getSystemService(Class<T> c);
 public abstract ComponentName startService(Intent i);
 public abstract ComponentName startForegroundService(Intent i);
 public abstract int checkSelfPermission(String p);""", "public abstract class")
c("android.content.ContextWrapper", """public ContextWrapper(Context base) {}
 public java.io.File getFilesDir() { return null; }
 public java.io.File getNoBackupFilesDir() { return null; }
 public java.io.File getCacheDir() { return null; }
 public android.content.pm.PackageManager getPackageManager() { return null; }
 public String getPackageName() { return null; }
 public Context getApplicationContext() { return this; }
 public <T> T getSystemService(Class<T> c) { return null; }
 public ComponentName startService(Intent i) { return null; }
 public ComponentName startForegroundService(Intent i) { return null; }
 public int checkSelfPermission(String p) { return 0; }""", "public class extends Context")
c("android.content.pm.PackageManager", "public static final int PERMISSION_GRANTED = 0;\n public Intent getLaunchIntentForPackage(String p) { return null; }", "public class", "import android.content.Intent;")
c("android.content.pm.ServiceInfo", "public static final int FOREGROUND_SERVICE_TYPE_MICROPHONE = 128;")
c("android.app.Service", """public Service() { super(null); }
 public void onCreate() {}
 public abstract android.os.IBinder onBind(Intent i);
 public int onStartCommand(Intent i, int flags, int startId) { return 0; }
 public void onDestroy() {}
 public final void startForeground(int id, Notification n) {}
 public final void startForeground(int id, Notification n, int type) {}
 public final void stopForeground(int flags) {}
 public final void stopSelf() {}
 public static final int START_NOT_STICKY = 2;
 public static final int STOP_FOREGROUND_REMOVE = 1;""", "public abstract class extends android.content.ContextWrapper", "import android.content.Intent;")
c("android.app.Notification", """public static final int VISIBILITY_PRIVATE = 0;
 public static final String CATEGORY_SERVICE = "service";
 public static final String CATEGORY_STATUS = "status";
 public static class Builder {
   public Builder(android.content.Context c, String channel) {}
   public Builder setSmallIcon(int i) { return this; }
   public Builder setContentTitle(CharSequence t) { return this; }
   public Builder setContentText(CharSequence t) { return this; }
   public Builder setSubText(CharSequence t) { return this; }
   public Builder setCategory(String c) { return this; }
   public Builder setVisibility(int v) { return this; }
   public Builder setOngoing(boolean b) { return this; }
   public Builder setOnlyAlertOnce(boolean b) { return this; }
   public Builder setShowWhen(boolean b) { return this; }
   public Builder setAutoCancel(boolean b) { return this; }
   public Builder addAction(Action a) { return this; }
   public Builder setContentIntent(PendingIntent p) { return this; }
   public Notification build() { return null; }
 }
 public static class Action {
   public static class Builder {
     public Builder(android.graphics.drawable.Icon icon, CharSequence title, PendingIntent intent) {}
     @Deprecated public Builder(int icon, CharSequence title, PendingIntent intent) {}
     public Action build() { return null; }
   }
 }""")
c("android.graphics.drawable.Icon", "")
c("android.net.Uri", "")
c("android.view.Surface", "")
c("android.app.NotificationChannel", """public NotificationChannel(String id, CharSequence name, int importance) {}
 public void setDescription(String d) {}
 public String getDescription() { return null; }
 public void setLockscreenVisibility(int v) {}
 public int getLockscreenVisibility() { return 0; }
 public void setSound(android.net.Uri uri, Object attrs) {}
 public void enableVibration(boolean b) {}""")
c("android.app.NotificationManager", """public static final int IMPORTANCE_LOW = 2; public static final int IMPORTANCE_DEFAULT = 3;
 public void createNotificationChannel(NotificationChannel c) {}
 public void notify(int id, Notification n) {}
 public void cancel(int id) {}
 public boolean areNotificationsEnabled() { return true; }""")
c("android.app.PendingIntent", """public static final int FLAG_UPDATE_CURRENT = 134217728; public static final int FLAG_IMMUTABLE = 67108864;
 public static PendingIntent getActivity(android.content.Context c, int code, android.content.Intent i, int flags) { return null; }
 public static PendingIntent getService(android.content.Context c, int code, android.content.Intent i, int flags) { return null; }""")
c("android.util.AtomicFile", """public AtomicFile(java.io.File f) {}
 public java.io.FileOutputStream startWrite() throws java.io.IOException { return null; }
 public void finishWrite(java.io.FileOutputStream s) {}
 public void failWrite(java.io.FileOutputStream s) {}
 public java.io.FileInputStream openRead() throws java.io.FileNotFoundException { return null; }
 public void delete() {}""")
c("android.media.AudioFormat", "public static final int CHANNEL_IN_MONO = 16; public static final int ENCODING_PCM_16BIT = 2;")
c("android.media.AudioRecordingConfiguration", "public boolean isClientSilenced() { return false; }")
c("android.media.AudioRecord", """public static final int STATE_INITIALIZED = 1;
 public android.media.AudioRecordingConfiguration getActiveRecordingConfiguration() { return null; }
 public AudioRecord(int source, int rate, int channelConfig, int format, int bufferBytes) {}
 public static int getMinBufferSize(int rate, int channelConfig, int format) { return 0; }
 public int getState() { return 1; }
 public void startRecording() {}
 public void stop() {}
 public void release() {}
 public int read(byte[] data, int offset, int size) { return 0; }""")
c("android.media.MediaRecorder", "public static final class AudioSource { public static final int MIC = 1; }")
c("android.media.MediaFormat", """public static final String MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"; public static final String KEY_AAC_PROFILE = "aac-profile";
 public static final String KEY_BIT_RATE = "bitrate"; public static final String KEY_MAX_INPUT_SIZE = "max-input-size"; public static final String KEY_MIME = "mime";
 public static MediaFormat createAudioFormat(String mime, int rate, int channels) { return null; }
 public final void setInteger(String k, int v) {}
 public final int getInteger(String k) { return 0; }
 public final String getString(String k) { return null; }""")
c("android.media.MediaCodecInfo", "public static final class CodecProfileLevel { public static final int AACObjectLC = 2; }")
c("android.media.MediaCodec", """public static final int CONFIGURE_FLAG_ENCODE = 1; public static final int BUFFER_FLAG_KEY_FRAME = 1; public static final int BUFFER_FLAG_END_OF_STREAM = 4; public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
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
 public final void releaseOutputBuffer(int i, boolean render) {}""", "public final class")
c("android.media.MediaDataSource", """public abstract int readAt(long position, byte[] buffer, int offset, int size) throws java.io.IOException;
 public abstract long getSize() throws java.io.IOException;""", "public abstract class implements java.io.Closeable")
c("android.media.MediaExtractor", """public static final int SAMPLE_FLAG_SYNC = 1;
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
 public void release() {}""")
c("android.media.MediaMuxer", """public static final class OutputFormat { public static final int MUXER_OUTPUT_MPEG_4 = 0; }
 public MediaMuxer(String path, int format) throws java.io.IOException {}
 public int addTrack(MediaFormat f) { return 0; }
 public void start() {}
 public void stop() {}
 public void release() {}
 public void writeSampleData(int t, java.nio.ByteBuffer b, MediaCodec.BufferInfo i) {}""")
c("android.media.MediaMetadataRetriever", """public static final int METADATA_KEY_DURATION = 9;
 public void setDataSource(String p) {}
 public void setDataSource(MediaDataSource s) {}
 public String extractMetadata(int k) { return null; }
 public void release() {}""")
c("android.media.MediaPlayer", "public void setDataSource(String p) throws java.io.IOException {}\n public void setDataSource(MediaDataSource s) throws java.io.IOException {}")
c("android.security.keystore.KeyGenParameterSpec", """public static final class Builder {
   public Builder(String alias, int purposes) {}
   public Builder setBlockModes(String... m) { return this; }
   public Builder setEncryptionPaddings(String... p) { return this; }
   public Builder setKeySize(int s) { return this; }
   public Builder setRandomizedEncryptionRequired(boolean b) { return this; }
   public Builder setUserAuthenticationRequired(boolean b) { return this; }
   public Builder setUnlockedDeviceRequired(boolean b) { return this; }
   public KeyGenParameterSpec build() { return null; }
 }""", "public final class implements java.security.spec.AlgorithmParameterSpec")
c("android.security.keystore.KeyProperties", """public static final int PURPOSE_ENCRYPT = 1; public static final int PURPOSE_DECRYPT = 2;
 public static final String BLOCK_MODE_GCM = "GCM"; public static final String ENCRYPTION_PADDING_NONE = "NoPadding"; public static final String KEY_ALGORITHM_AES = "AES";""")
c("org.json.JSONException", "public JSONException(String m) { super(m); }", "public class extends Exception")
c("org.json.JSONObject", """public static final Object NULL = new Object();
 public JSONObject() {}
 public JSONObject(String s) throws JSONException {}
 public JSONObject put(String n, Object v) throws JSONException { return this; }
 public JSONObject put(String n, long v) throws JSONException { return this; }
 public JSONObject put(String n, boolean v) throws JSONException { return this; }
 public String optString(String n, String fallback) { return fallback; }
 public String optString(String n) { return ""; }
 public long optLong(String n) { return 0; }
 public int optInt(String n) { return 0; }
 public boolean optBoolean(String n) { return false; }
 public JSONObject optJSONObject(String n) { return null; }
 public JSONArray optJSONArray(String n) { return null; }
 public String getString(String n) throws JSONException { return null; }
 public JSONObject getJSONObject(String n) throws JSONException { return null; }
 public JSONArray getJSONArray(String n) throws JSONException { return null; }
 public boolean has(String n) { return false; }
 public boolean isNull(String n) { return true; }""")
c("org.json.JSONArray", """public JSONArray() {}
 public JSONArray(String s) throws JSONException {}
 public JSONArray(java.util.Collection<?> c) {}
 public int length() { return 0; }
 public String getString(int i) throws JSONException { return null; }
 public JSONObject getJSONObject(int i) throws JSONException { return null; }
 public JSONArray put(Object v) { return this; }""")

c("android.app.job.JobParameters", "")
c("android.app.job.JobService", """public abstract boolean onStartJob(JobParameters p);
 public abstract boolean onStopJob(JobParameters p);
 public final void jobFinished(JobParameters p, boolean reschedule) {}
 public final android.os.IBinder onBind(android.content.Intent i) { return null; }""", "public abstract class extends android.app.Service", "")
c("android.app.job.JobInfo", """public static final class Builder {
   public Builder(int id, android.content.ComponentName name) {}
   public Builder setPeriodic(long ms) { return this; }
   public Builder setPersisted(boolean b) { return this; }
   public JobInfo build() { return null; }
 }""")
c("android.app.job.JobScheduler", "public int schedule(JobInfo info) { return 0; }")
for fq, (kind, body, imports) in S.items():
    pkg, name = fq.rsplit(".", 1)
    # kind may carry "extends/implements"
    import re
    head = re.sub(r"\b(class|interface)\b", lambda m: m.group(1) + " " + name, kind, count=1)
    path = os.path.join(root, *pkg.split("."), name + ".java")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(f"package {pkg};\n{imports}\n{head} {{\n{body}\n}}\n")
print(len(S), "classes")
