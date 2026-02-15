SocksDroid
==========

## SOCKS5 client for Android 5.0+ using VpnService

This is an updated version of [SocksDroid by PeterCxy](https://github.com/PeterCxy/SocksDroid) to support modern Android devices.

The project is in maintenance mode: no new features are planned, only bug fixes and compatibility upgrades.

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" width="200">](https://play.google.com/store/apps/details?id=net.typeblog.socks)

## 🆕 Broadcast API для динамического переключения прокси

Приложение поддерживает динамическое переключение настроек прокси через broadcast-сообщения. Это позволяет другим приложениям или скриптам автоматически изменять конфигурацию прокси без участия пользователя.

### Быстрый пример

```bash
# Переключение на новый прокси через ADB
adb shell am broadcast -a net.typeblog.socks.PROXY_CHANGE \
  --es proxy_server "192.168.1.100" \
  --ei proxy_port 1080
```

### Документация

Полная документация доступна в файле [PROXY_BROADCAST.md](PROXY_BROADCAST.md)

### Тестирование

Для тестирования функционала используйте скрипт:

```bash
./test_proxy_broadcast.sh
```

Скрипт предоставляет интерактивное меню с различными тестовыми сценариями.
