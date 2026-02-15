package net.typeblog.socks.soket_server

import android.content.Context
import android.net.VpnService
import android.util.Log
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.Profile
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.ProxyInfo
import net.typeblog.socks.util.ProxyUrlParser
import net.typeblog.socks.util.Utility
import net.typeblog.socks.util.VpnStatusHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.net.InetSocketAddress

class ProxySocketHandler(
    private val socket: Socket,
    private val context: Context
) : Runnable {

    private val TAG = "ProxySocketHandler"
    private val READ_TIMEOUT = 30000 // 30 seconds
    private val VPN_CONNECTION_TIMEOUT_MS = 30000L // 30 seconds timeout for VPN connection (increased from 10s)

    override fun run() {
        var connectionId: Long? = null

        try {
            socket.setSoTimeout(READ_TIMEOUT)
            val message = readRequest()

            if (message == null) {
                Log.w(TAG, "Failed to read request, registering as error")
                connectionId =
                    SocketRequestManager.registerRequest(socket, "error:no proxy request, exit")
                socket.setSoTimeout(0)

                if (!socket.isClosed && connectionId != null) {
                    SocketRequestManager.sendResponseToAll("error:no proxy request, exit")
                }
                return
            }


            Log.d(TAG, "message received: $message")
            // Parse Proxy
            val proxyInfo = ProxyUrlParser.parse(message)

            if (proxyInfo == null) {
                Log.w(TAG, "Failed to parse proxy URL: $message")
                connectionId = SocketRequestManager.registerRequest(socket, message)
                socket.setSoTimeout(0)

                // Send error response for unparseable URL
                if (!socket.isClosed && connectionId != null) {
                    SocketRequestManager.sendResponseToAll("error:invalid proxy URL format")
                }
                return
            }

            Log.d(
                TAG,
                "Successfully parsed proxy: ${proxyInfo.type.scheme}://${proxyInfo.host}:${proxyInfo.port}"
            )
            connectionId = SocketRequestManager.registerRequest(socket, message, proxyInfo)
            socket.setSoTimeout(0)

            // Test proxy connectivity before starting VPN (optional but helpful for diagnostics)
            val proxyReachable = testProxyConnectivity(proxyInfo)
            if (!proxyReachable) {
                Log.w(TAG, "Прокси сервер ${proxyInfo.host}:${proxyInfo.port} может быть недоступен, но продолжаем попытку подключения")
            } else {
                Log.d(TAG, "Прокси сервер ${proxyInfo.host}:${proxyInfo.port} доступен")
            }

            // Create and activate profile
            val success = createAndActivateProfile(proxyInfo)

            if (!socket.isClosed && connectionId != null) {
                if (success) {
                    Log.d(TAG, "Proxy activated successfully, sending success response")
                    SocketRequestManager.sendResponseToAll(message)
                } else {
                    Log.e(TAG, "Failed to activate proxy, sending error response")
                    SocketRequestManager.sendResponseToAll("error:failed to activate proxy")
                }
            }

        } catch (e: InterruptedException) {
            SocketRequestManager.sendResponseToAll("error: read request timeout, exit")
            Thread.currentThread().interrupt()
            Log.w(TAG, "Thread interrupted during wait")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing connection", e)
            try {
                connectionId = SocketRequestManager.registerRequest(socket, "ERROR")
                socket.setSoTimeout(0)
                if (!socket.isClosed && connectionId != null) {
                    SocketRequestManager.sendResponseToAll("error:unknown error, exit")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error registering request on exception, close socket", ex)
                closeSocket()
            }
        } finally {
            if (connectionId == null) {
                closeSocket()
            }
        }
    }

    private fun readRequest(): String? {
        if (socket.isClosed) {
            Log.e(TAG, "Socket closed while reading request")
            return null
        }

        return try {
            val reader = BufferedReader(
                InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)
            )
            val line = reader.readLine()
            line?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Read timeout", e)
            null
        } catch (e: SocketException) {
            Log.e(TAG, "Socket error while reading request: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading request: ${e.message}", e)
            null
        }
    }

    private fun createAndActivateProfile(proxyInfo: ProxyInfo): Boolean {
        try {
            // Check VPN permission first
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                Log.e(TAG, "VPN permission not granted, cannot activate proxy")
                return false
            }

            // Generate profile name from proxy info
            val profileName = generateProfileName(proxyInfo)

            // Get or create profile
            val profileManager = ProfileManager(context)
            var profile = profileManager.getProfile(profileName)

            if (profile == null) {
                // Create new profile
                profile = profileManager.addProfile(profileName)
                if (profile == null) {
                    Log.e(TAG, "Failed to create profile: $profileName")
                    return false
                }
                Log.d(TAG, "Created new profile: $profileName")
            } else {
                Log.d(TAG, "Using existing profile: $profileName")
            }

            // Configure profile with proxy settings
            profile.setServer(proxyInfo.host)
            profile.setPort(proxyInfo.port)

            // Set authentication if provided
            if (proxyInfo.requiresAuth()) {
                profile.setIsUserpw(true)
                profile.setUsername(proxyInfo.username ?: "")
                profile.setPassword(proxyInfo.password ?: "")
            } else {
                profile.setIsUserpw(false)
            }

            // Set default values for other settings
            profile.setRoute(Constants.ROUTE_ALL)
            profile.setDns("8.8.8.8")
            profile.setDnsPort(53)
            profile.setHasIPv6(false)
            profile.setHasUDP(false)
            profile.setIsPerApp(false)
            profile.setAutoConnect(false)

            // Switch to this profile as default
            profileManager.switchDefault(profileName)

            // Start VPN service
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            Log.d(TAG, "Запуск VPN сервиса")
            Log.d(TAG, "  Профиль: $profileName")
            Log.d(TAG, "  Прокси: ${proxyInfo.host}:${proxyInfo.port}")
            Log.d(TAG, "  Авторизация: ${if (proxyInfo.requiresAuth()) "да (${proxyInfo.username})" else "нет"}")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            
            try {
                Utility.startVpn(context, profile)
                Log.d(TAG, "VPN сервис запущен, ожидание активации интерфейса...")
            } catch (e: RuntimeException) {
                Log.e(TAG, "ОШИБКА: Не удалось запустить VPN сервис", e)
                return false
            }

            // Wait a bit for the service to start and interface to be created
            Thread.sleep(2000) // Увеличено до 2 секунд для инициализации

            // Wait for VPN connection with timeout
            Log.d(TAG, "Ожидание активации VPN интерфейса (таймаут: ${VPN_CONNECTION_TIMEOUT_MS}ms)...")
            val connected = VpnStatusHelper.waitForVpnConnectionWithServiceCheck(
                context,
                VPN_CONNECTION_TIMEOUT_MS,
                500L
            )

            if (connected) {
                Log.d(TAG, "✓ VPN подключен успешно!")
                return true
            } else {
                Log.e(TAG, "VPN connection timeout after ${VPN_CONNECTION_TIMEOUT_MS}ms")
                Log.e(TAG, "Possible causes:")
                Log.e(TAG, "  1. Proxy server ${proxyInfo.host}:${proxyInfo.port} is unreachable")
                if (proxyInfo.requiresAuth()) {
                    Log.e(TAG, "  2. Invalid credentials (username: ${proxyInfo.username})")
                }
                Log.e(TAG, "  3. Network connectivity issues")
                Log.e(TAG, "  4. VPN service failed to start")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating and activating profile", e)
            return false
        }
    }

    private fun generateProfileName(proxyInfo: ProxyInfo): String {
        val hostPart = proxyInfo.host.replace(".", "_").replace(":", "_")
        val typePart = proxyInfo.type.scheme.uppercase()
        return "Socket_${typePart}_${hostPart}_${proxyInfo.port}"
    }

    /**
     * Проверяет доступность прокси-сервера перед запуском VPN
     * Это помогает диагностировать проблемы с подключением
     * 
     * @param proxyInfo Информация о прокси
     * @return true если сервер доступен, false иначе
     */
    private fun testProxyConnectivity(proxyInfo: ProxyInfo): Boolean {
        return try {
            Log.d(TAG, "Проверка доступности прокси ${proxyInfo.host}:${proxyInfo.port}...")
            val socket = Socket()
            socket.connect(InetSocketAddress(proxyInfo.host, proxyInfo.port), 5000) // 5 секунд таймаут
            socket.close()
            Log.d(TAG, "Прокси сервер ${proxyInfo.host}:${proxyInfo.port} доступен")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подключиться к прокси ${proxyInfo.host}:${proxyInfo.port}: ${e.message}")
            false
        }
    }

    private fun closeSocket() {
        try {
            if (!socket.isClosed) {
                if (!socket.isInputShutdown) {
                    socket.shutdownInput()
                }
                socket.close()
                Log.d(TAG, "Socket closed due to registration failure")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}", e)
        }
    }
}
