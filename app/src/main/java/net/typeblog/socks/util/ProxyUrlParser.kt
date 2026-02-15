package net.typeblog.socks.util

import android.util.Log

/**
 availible formats
 * - socks5://user:pass@host:port (standard format)
 * - socks5://host:port:user:pass (alternative format)
 * - socks5://host:port
 * - socks4://user:pass@host:port
 * - socks4://host:port:user:pass
 * - socks4://host:port
 * - http://user:pass@host:port
 * - http://host:port:user:pass
 * - http://host:port
 * - https://user:pass@host:port
 * - https://host:port:user:pass
 * - https://host:port
 */
object ProxyUrlParser {
    private const val TAG = "ProxyUrlParser"

    fun parse(url: String): ProxyInfo? {
        if (url.isBlank()) {
            Log.w(TAG, "Empty URL provided")
            return null
        }
        
        val trimmedUrl = url.trim()
        
        try {
            val schemeEndIndex = trimmedUrl.indexOf("://")
            if (schemeEndIndex == -1) {
                Log.w(TAG, "No scheme found in URL: $trimmedUrl")
                return null
            }

            val scheme = trimmedUrl.substring(0, schemeEndIndex).lowercase()
            val proxyType = ProxyType.fromScheme(scheme)
            
            if (proxyType == null) {
                Log.w(TAG, "Unsupported proxy type: $scheme")
                return null
            }

            val afterScheme = trimmedUrl.substring(schemeEndIndex + 3)

            // Try standard format first: user:pass@host:port
            val atIndex = afterScheme.lastIndexOf('@')
            val hasStandardAuth = atIndex != -1
            
            val username: String?
            val password: String?
            val host: String
            val portStr: String
            
            if (hasStandardAuth) {
                // Standard format: user:pass@host:port
                val authPart = afterScheme.substring(0, atIndex)
                val hostPortPart = afterScheme.substring(atIndex + 1)
                
                val (user, pass) = parseAuth(authPart)
                username = user
                password = pass
                
                val colonIndex = hostPortPart.lastIndexOf(':')
                if (colonIndex == -1) {
                    Log.w(TAG, "No port found in URL: $trimmedUrl")
                    return null
                }
                
                host = hostPortPart.substring(0, colonIndex)
                portStr = hostPortPart.substring(colonIndex + 1)
            } else {
                // Try alternative format: host:port:user:pass
                val colonCount = afterScheme.count { it == ':' }
                
                if (colonCount >= 3) {
                    // Alternative format: host:port:user:pass
                    val lastColonIndex = afterScheme.lastIndexOf(':')
                    val secondLastColonIndex = afterScheme.lastIndexOf(':', lastColonIndex - 1)
                    
                    if (secondLastColonIndex == -1) {
                        Log.w(TAG, "Invalid alternative format in URL: $trimmedUrl")
                        return null
                    }
                    
                    val hostPortPart = afterScheme.substring(0, secondLastColonIndex)
                    val authPart = afterScheme.substring(secondLastColonIndex + 1)
                    
                    val (user, pass) = parseAuth(authPart)
                    username = user
                    password = pass
                    
                    val colonIndex = hostPortPart.lastIndexOf(':')
                    if (colonIndex == -1) {
                        Log.w(TAG, "No port found in URL: $trimmedUrl")
                        return null
                    }
                    
                    host = hostPortPart.substring(0, colonIndex)
                    portStr = hostPortPart.substring(colonIndex + 1)
                } else if (colonCount == 1) {
                    // Simple format: host:port (no auth)
                    val colonIndex = afterScheme.lastIndexOf(':')
                    username = null
                    password = null
                    host = afterScheme.substring(0, colonIndex)
                    portStr = afterScheme.substring(colonIndex + 1)
                } else {
                    Log.w(TAG, "Invalid URL format: $trimmedUrl")
                    return null
                }
            }
            
            if (host.isBlank()) {
                Log.w(TAG, "Empty host in URL: $trimmedUrl")
                return null
            }
            
            val port = try {
                portStr.toInt()
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Invalid port number: $portStr")
                return null
            }
            
            if (port < 1 || port > 65535) {
                Log.w(TAG, "Port out of range: $port")
                return null
            }
            
            return ProxyInfo(
                type = proxyType,
                host = host,
                port = port,
                username = username,
                password = password
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing proxy URL: $trimmedUrl", e)
            return null
        }
    }
    
    private fun parseAuth(authPart: String): Pair<String?, String?> {
        val colonIndex = authPart.indexOf(':')
        
        return if (colonIndex == -1) {
            // Only username without password
            Pair(authPart.ifEmpty { null }, null)
        } else {
            val user = authPart.substring(0, colonIndex)
            val pass = authPart.substring(colonIndex + 1)
            Pair(user.ifEmpty { null }, pass.ifEmpty { null })
        }
    }

    fun isValid(url: String): Boolean {
        return parse(url) != null
    }
}

