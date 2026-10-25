package android.content;

public abstract class Context {
public abstract java.io.File getFilesDir();
 public abstract java.io.File getNoBackupFilesDir();
 public abstract java.io.File getCacheDir();
 public abstract android.content.pm.PackageManager getPackageManager();
 public abstract String getPackageName();
 public abstract Context getApplicationContext();
 public abstract <T> T getSystemService(Class<T> c);
 public abstract ComponentName startService(Intent i);
 public abstract ComponentName startForegroundService(Intent i);
 public abstract int checkSelfPermission(String p);
}
