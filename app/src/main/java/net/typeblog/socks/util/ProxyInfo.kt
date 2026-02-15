package net.typeblog.socks.util

/**
 * Класс для хранения информации о прокси-сервере
 */
data class ProxyInfo(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null
) {

    override fun toString(): String {
        val authPart = if (requiresAuth()) "$username:$password@" else ""
        return "${type.scheme}://$authPart$host:$port"
    }
    fun requiresAuth(): Boolean {
        return username != null && password != null
    }
}

enum class ProxyType(val scheme: String) {
    SOCKS4("socks4"),
    SOCKS5("socks5"),
    HTTP("http"),
    HTTPS("https");

    companion object {
        fun fromScheme(scheme: String): ProxyType? {
            return entries.find {
                it.scheme.equals(scheme, ignoreCase = true)
            }
        }
    }
}



