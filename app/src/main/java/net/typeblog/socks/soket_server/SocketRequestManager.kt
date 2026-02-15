package net.typeblog.socks.soket_server

import android.util.Log
import net.typeblog.socks.util.ProxyInfo
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for managing active socket requests
 * Stores sockets and messages until response is sent
 */
object SocketRequestManager {
    private const val TAG = "SocketRequestManager"
    
    data class PendingRequest(
        val socket: Socket,
        val message: String,
        val connectionId: Long,
        val proxyInfo: ProxyInfo? = null
    )
    
    private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()
    private var connectionIdCounter = 0L
    
    fun registerRequest(socket: Socket, message: String): Long {
        return registerRequest(socket, message, null)
    }
    
    fun registerRequest(socket: Socket, message: String, proxyInfo: ProxyInfo?): Long {
        val id = connectionIdCounter++
        pendingRequests[id] = PendingRequest(socket, message, id, proxyInfo)
        Log.d(TAG, "Request registered with ID: $id, total active: ${pendingRequests.size}")
        if (proxyInfo != null) {
            Log.d(TAG, "ProxyInfo: type=${proxyInfo.type}, host=${proxyInfo.host}, port=${proxyInfo.port}, auth=${proxyInfo.requiresAuth()}")
        }
        return id
    }

    fun sendResponseToAll(message: String) {
        val requests = pendingRequests.values.toList()
        Log.d(TAG, "Sending response to ${requests.size} active requests")
        
        requests.forEach { pendingRequest ->
            if (sendResponseToSocket(pendingRequest.socket, message)) {
                Log.d(TAG, "Response sent, socket remains open for request ${pendingRequest.connectionId}")
            }
        }
    }
    private fun sendResponseToSocket(socket: Socket, message: String): Boolean {
        if (socket.isClosed || socket.isOutputShutdown) {
            Log.w(TAG, "Attempt to send response to closed socket")
            return false
        }
        
        try {
            Log.d(TAG, "Sending response: $message")
            
            val writer = OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8)
            val messageWithNewline = if (message.endsWith("\n")) message else "$message\n"
            writer.write(messageWithNewline)
            writer.flush()
            socket.outputStream.flush()
            
            Log.d(TAG, "Response sent successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending response to socket", e)
            return false
        }
    }
    fun clearAll() {
        pendingRequests.values.forEach { request ->
            if (!request.socket.isClosed) {
                try {
                    request.socket.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing socket during cleanup", e)
                }
            }
        }
        pendingRequests.clear()
        Log.d(TAG, "All active requests cleared")
    }
}
