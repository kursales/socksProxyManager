package net.typeblog.socks

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.typeblog.socks.soket_server.ProxySocketService

class MainActivity : Activity() {
    private val BROADCAST_ACTION = "net.typeblog.socks.PROXY_CHANGE"
    private val VPN_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.getFragmentManager().beginTransaction().replace(R.id.content, ProfileFragment())
            .commit()
        
        // Проверяем и запрашиваем разрешение на уведомления (требуется для foreground сервисов на Android 13+)
        checkAndRequestNotificationPermission()
        
        // Проверяем и запрашиваем разрешение VPN при запуске
        checkAndRequestVpnPermission()
    }
    
    /**
     * Проверяет наличие разрешения на уведомления и запрашивает его при необходимости
     * Требуется для Android 13+ (API 33+) для работы foreground сервисов
     */
    private fun checkAndRequestNotificationPermission() {
        // Разрешение требуется только на Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Разрешения нет - показываем диалог с объяснением
                AlertDialog.Builder(this)
                    .setTitle("Разрешение на уведомления")
                    .setMessage(
                        "Для работы Socket API требуется разрешение на показ уведомлений.\n\n" +
                        "Это разрешение необходимо для:\n" +
                        "• Работы Socket сервера в фоновом режиме\n" +
                        "• Отображения статуса сервиса в уведомлениях\n\n" +
                        "Предоставить разрешение?"
                    )
                    .setPositiveButton("Да") { _, _ ->
                        // Запрашиваем разрешение
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            NOTIFICATION_PERMISSION_REQUEST_CODE
                        )
                    }
                    .setNegativeButton("Позже") { dialog, _ ->
                        dialog.dismiss()
                        Toast.makeText(
                            this,
                            "Socket API будет работать, но уведомления могут не отображаться",
                            Toast.LENGTH_LONG
                        ).show()
                        // Все равно запускаем сервис, но уведомления могут не работать
                        startProxySocketService()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                // Разрешение уже есть
                Log.d("MainActivity", "Разрешение на уведомления уже предоставлено")
                startProxySocketService()
            }
        } else {
            // На Android 12 и ниже разрешение не требуется
            startProxySocketService()
        }
    }
    
    /**
     * Обработка результата запроса разрешений
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "Разрешение на уведомления предоставлено")
                    Toast.makeText(
                        this,
                        "Разрешение на уведомления предоставлено",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Запускаем сервис после получения разрешения
                    startProxySocketService()
                } else {
                    Log.w("MainActivity", "Разрешение на уведомления отклонено пользователем")
                    Toast.makeText(
                        this,
                        "Разрешение отклонено. Socket API будет работать, но уведомления могут не отображаться",
                        Toast.LENGTH_LONG
                    ).show()
                    // Все равно запускаем сервис
                    startProxySocketService()
                }
            }
        }
    }
    
    /**
     * Запускает ProxySocketService для работы Socket API
     */
    private fun startProxySocketService() {
        try {
            val intent = Intent(this, ProxySocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "error start ProxySocketService", e)
            Toast.makeText(
                this,
                "Ошибка запуска Socket сервера: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkAndRequestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        
        if (prepareIntent != null) {
            // Разрешения нет - показываем диалог с объяснением
            AlertDialog.Builder(this)
                .setTitle("Разрешение VPN")
                .setMessage(
                    "Для работы приложения требуется разрешение на создание VPN-соединения.\n\n" +
                    "Это разрешение необходимо для:\n" +
                    "• Маршрутизации трафика через SOCKS прокси\n" +
                    "• Автоматического переключения прокси через broadcast\n\n" +
                    "Предоставить разрешение?"
                )
                .setPositiveButton("Да") { _, _ ->
                    // Запрашиваем разрешение
                    try {
                        startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Ошибка при запросе разрешения VPN", e)
                        Toast.makeText(
                            this,
                            "Ошибка при запросе разрешения VPN",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("Позже") { dialog, _ ->
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        "Broadcast API будет работать только после предоставления разрешения",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .setCancelable(false)
                .show()
        } else {
            // Разрешение уже есть
            Log.d("MainActivity", "VPN разрешение уже предоставлено")
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Log.i("MainActivity", "VPN разрешение предоставлено")
                Toast.makeText(
                    this,
                    "VPN разрешение предоставлено. Broadcast API теперь работает полностью.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Log.w("MainActivity", "VPN разрешение отклонено пользователем")
                Toast.makeText(
                    this,
                    "Разрешение отклонено. Автоматический запуск VPN через broadcast будет недоступен.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
