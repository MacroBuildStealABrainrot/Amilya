package android.app;
import android.content.Intent;
public abstract class Service extends android.content.ContextWrapper {
public Service() { super(null); }
 public void onCreate() {}
 public abstract android.os.IBinder onBind(Intent i);
 public int onStartCommand(Intent i, int flags, int startId) { return 0; }
 public void onDestroy() {}
 public final void startForeground(int id, Notification n) {}
 public final void startForeground(int id, Notification n, int type) {}
 public final void stopForeground(int flags) {}
 public final void stopSelf() {}
 public static final int START_NOT_STICKY = 2;
 public static final int STOP_FOREGROUND_REMOVE = 1;
}
