// Скопіюй у secrets.h (його не комітимо) і впиши свою Wi-Fi мережу —
// ту саму, до якої підключений телефон зі Спіді.
#pragma once

#define WIFI_SSID "MyHomeWiFi"
#define WIFI_PASS "password"

// Якщо до домашньої мережі не вдалося підключитися — платформа створює
// власну точку доступу (для налагодження; інтернету в ній немає).
#define FALLBACK_AP_SSID "Speedy-Robot"
#define FALLBACK_AP_PASS "speedy123"
