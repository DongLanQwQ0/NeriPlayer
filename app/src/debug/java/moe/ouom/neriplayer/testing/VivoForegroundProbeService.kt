package moe.ouom.neriplayer.testing

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.annotation.RequiresApi

/** Explicit instrumentation fixture; never touches the player, queue or library. */
@RequiresApi(31)
class VivoForegroundProbeService : Service() {
    companion object {
        const val ID = 0x4e50f047
        // Keep the fixture payload in this process, just as a real playback service builds it locally.
        @Volatile var nextNotification: Notification? = null
        @Volatile var running = false
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("VivoFgProbe", "service onStartCommand")
        val notification = nextNotification
        nextNotification = null
        if (notification == null) {
            android.util.Log.e("VivoFgProbe", "service notification missing")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        android.util.Log.i("VivoFgProbe", "service startForeground completed")
        running = true
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        nextNotification = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
