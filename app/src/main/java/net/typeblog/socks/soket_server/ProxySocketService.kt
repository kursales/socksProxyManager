package net.typeblog.socks.soket_server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import net.typeblog.socks.MainActivity
import net.typeblog.socks.R
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class ProxySocketService : Service() {

    private val TAG = "ProxySocketService"
    private val CHANNEL_ID = "proxy_socket_service_channel"
    private val NOTIFICATION_ID = 2

    companion object {
        const val DEFAULT_PORT = 10888 // TCP port for socket server
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var connectionCounter = 0L
    private var serverPort = DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ProxySocketService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ProxySocketService started")

        if (isRunning.get()) {
            Log.d(TAG, "Service already running")
            return START_STICKY
        }

        serverPort = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT

        startForeground(NOTIFICATION_ID, createNotification())
        startSocketServer()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ProxySocketService destroyed")
        stopSocketServer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Socket Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service for managing proxy via socket"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            intentFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Socket server for proxy management is active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startSocketServer() {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running")
            return
        }

        try {
            serverSocket = ServerSocket(serverPort, 0, InetAddress.getByName("127.0.0.1"))
            serverSocket?.reuseAddress = true

            isRunning.set(true)
            Log.i(TAG, "TCP Socket server started on port: $serverPort (127.0.0.1)")

            Thread({
                listenForConnections()
            }, "ProxySocketServerThread").start()

        } catch (e: BindException) {
            Log.e(TAG, "Port $serverPort already in use. Try another port.", e)
            isRunning.set(false)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting socket server", e)
            isRunning.set(false)
            stopSelf()
        }
    }

    private fun listenForConnections() {
        val socket = serverSocket ?: return

        while (isRunning.get()) {
            try {
                Log.d(TAG, "Waiting for connection...")
                val clientSocket = socket.accept()

                Log.d(TAG, "New connection received")

                val connectionId = connectionCounter++
                Thread({
                    ProxySocketHandler(clientSocket, applicationContext).run()
                }, "ProxySocketHandler-$connectionId").start()

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection", e)
                } else {
                    Log.d(TAG, "Server stopped")
                }
            }
        }
    }

    private fun stopSocketServer() {
        if (!isRunning.get()) {
            return
        }

        Log.d(TAG, "Stopping socket server...")
        isRunning.set(false)

        SocketRequestManager.clearAll()

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        Log.d(TAG, "Socket server stopped")
    }
}