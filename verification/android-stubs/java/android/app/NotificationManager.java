package android.app;

public class NotificationManager {
public static final int IMPORTANCE_LOW = 2; public static final int IMPORTANCE_DEFAULT = 3;
 public void createNotificationChannel(NotificationChannel c) {}
 public void notify(int id, Notification n) {}
 public void cancel(int id) {}
 public boolean areNotificationsEnabled() { return true; }
}
