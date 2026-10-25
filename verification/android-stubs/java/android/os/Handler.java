package android.os;

public class Handler {
public Handler(Looper l) {}
 public final boolean post(Runnable r) { return true; }
 public final boolean postDelayed(Runnable r, long d) { return true; }
 public final void removeCallbacks(Runnable r) {}
 public final void removeCallbacksAndMessages(Object token) {}
}
