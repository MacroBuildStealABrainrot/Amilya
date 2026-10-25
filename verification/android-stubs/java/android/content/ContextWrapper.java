package android.content;

public class ContextWrapper extends Context {
public ContextWrapper(Context base) {}
 public java.io.File getFilesDir() { return null; }
 public java.io.File getNoBackupFilesDir() { return null; }
 public java.io.File getCacheDir() { return null; }
 public android.content.pm.PackageManager getPackageManager() { return null; }
 public String getPackageName() { return null; }
 public Context getApplicationContext() { return this; }
 public <T> T getSystemService(Class<T> c) { return null; }
 public ComponentName startService(Intent i) { return null; }
 public ComponentName startForegroundService(Intent i) { return null; }
 public int checkSelfPermission(String p) { return 0; }
}
