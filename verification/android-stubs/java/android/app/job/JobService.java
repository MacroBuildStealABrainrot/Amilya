package android.app.job;

public abstract class JobService extends android.app.Service {
public abstract boolean onStartJob(JobParameters p);
 public abstract boolean onStopJob(JobParameters p);
 public final void jobFinished(JobParameters p, boolean reschedule) {}
 public final android.os.IBinder onBind(android.content.Intent i) { return null; }
}
