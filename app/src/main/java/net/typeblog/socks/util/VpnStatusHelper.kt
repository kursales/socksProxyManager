package net.typeblog.socks.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import net.typeblog.socks.IVpnService
import net.typeblog.socks.SocksVpnService

/**
 * VPN status checking utility
 */
object VpnStatusHelper {
    
    private const val TAG = "VpnStatusHelper"

    fun interface VpnStatusCallback {
        fun onStatusChecked(isRunning: Boolean)
    }

    fun checkVpnStatus(context: Context, callback: VpnStatusCallback) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val binder = IVpnService.Stub.asInterface(service)
                    val isRunning = binder.isRunning
                    callback.onStatusChecked(isRunning)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check VPN status", e)
                    callback.onStatusChecked(false)
                } finally {
                    try {
                        context.unbindService(this)
                    } catch (ignored: Exception) {
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                callback.onStatusChecked(false)
            }
        }
        
        try {
            val intent = Intent(context, SocksVpnService::class.java)
            context.bindService(intent, connection, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to VPN service", e)
            callback.onStatusChecked(false)
        }
    }

    fun isAnyVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            val activeIsVpn = activeNetwork?.let { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } ?: false

            val anyVpnNetwork = cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            
            activeIsVpn || anyVpnNetwork
        } else {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }
    }

    fun waitForVpnConnectionWithServiceCheck(
        context: Context,
        timeoutMs: Long,
        checkInterval: Long = 500L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val vpnInterfaceActive = isAnyVpnActive(context)
            
            if (vpnInterfaceActive) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "VPN interface active after ${elapsed}ms")
                return true
            }
            
            try {
                Thread.sleep(checkInterval)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "VPN connection wait interrupted")
                return false
            }
        }
        
        val finalVpnActive = isAnyVpnActive(context)
        val finalTun2socksRunning = isTun2SocksRunning(context)
        
        Log.e(TAG, "VPN connection timeout (${timeoutMs}ms). VPN interface: ${if (finalVpnActive) "active" else "inactive"}, tun2socks: ${if (finalTun2socksRunning) "running" else "not found"}")
        
        return false
    }
    
    /**
     * Checks if tun2socks process is running by verifying PID file and process existence
     */
    fun isTun2SocksRunning(context: Context): Boolean {
        val pidFile = java.io.File(context.filesDir, "tun2socks.pid")
        if (!pidFile.exists()) {
            return false
        }
        
        return try {
            val pid = pidFile.readText().trim().toInt()
            java.io.File("/proc/$pid").exists()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Synchronously checks VPN service status via bind with timeout
     */
    fun checkVpnServiceStatus(context: Context, timeoutMs: Long = 2000L): Boolean {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val binder = IVpnService.Stub.asInterface(service)
                    result = binder.isRunning
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check VPN service status", e)
                    result = false
                } finally {
                    try {
                        context.unbindService(this)
                    } catch (ignored: Exception) {
                    }
                    latch.countDown()
                }
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                result = false
                latch.countDown()
            }
        }
        
        try {
            val intent = Intent(context, SocksVpnService::class.java)
            if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                Log.w(TAG, "Failed to bind to VPN service")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check VPN service status", e)
            return false
        }
        
        return result
    }
    
    data class VpnInfo(
        val isVpnActive: Boolean,
        val hasInternet: Boolean,
        val info: String
    )
}

fun Context.isVpnActive(): Boolean = VpnStatusHelper.isAnyVpnActive(this)

fun Context.checkVpnStatus(callback: VpnStatusHelper.VpnStatusCallback) {
    VpnStatusHelper.checkVpnStatus(this, callback)
}

suspend fun Context.checkVpnStatusSuspend(): Boolean {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        VpnStatusHelper.checkVpnStatus(this) { isRunning ->
            continuation.resume(isRunning) { /* cleanup if needed */ }
        }
    }
}

