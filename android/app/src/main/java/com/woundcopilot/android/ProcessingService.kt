package com.woundcopilot.android
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Visible foreground lifetime for user-initiated processing; checkpoints survive termination. */
class ProcessingService:Service(){
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing","Report processing",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"processing").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Amilya").setContentText("Preparing your work").setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        if(android.os.Build.VERSION.SDK_INT>=29)startForeground(4202,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(4202,notification)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){scope.coroutineContext.cancelChildren();stopSelf()}
    companion object{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        fun start(context:Context){ContextCompat.startForegroundService(context,Intent(context,ProcessingService::class.java))}
        fun stop(context:Context){context.stopService(Intent(context,ProcessingService::class.java))}
    }
}
