package android.app;

public class Notification {
public static final int VISIBILITY_PRIVATE = 0;
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
 }
}
