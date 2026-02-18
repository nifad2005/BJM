package com.example.bjm

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ChatService : Service() {
    private var mqttManager: MqttManager? = null

    override fun onCreate() {
        super.onCreate()
        // 1. Call startForeground IMMEDIATELY to avoid crashes on startup
        showForegroundNotification()

        // 2. Initialize MQTT in the background
        val db = AppDatabase.getDatabase(this)
        mqttManager = MqttManager.getInstance(this, db.chatDao())
        mqttManager?.connect()
    }

    private fun showForegroundNotification() {
        val channelId = "chat_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BJM is active")
            .setContentText("Listening for messages...")
            // Use a standard system icon which is guaranteed to work for notifications
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Handle Android 14+ foreground service type requirements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // We don't disconnect the singleton here to allow it to persist if needed,
        // but if the service is destroyed, we can at least null the reference.
        mqttManager = null
        super.onDestroy()
    }
}
