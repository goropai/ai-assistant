// Прошивка колісної платформи Спіді.
//
// HTTP-протокол (той самий, що в RobotPlatform.kt):
//   GET /move?dir=forward|backward|left|right&ms=0..3000&speed=0..100
//   GET /stop
//   GET /status  -> {"ok":true,...}
//
// Безпека: кожна команда руху має обмежену тривалість. Мотори зупиняє сама
// плата, навіть якщо телефон завис або зник Wi-Fi.
//
// Драйвер: L9110S (2 канали на платі). Шасі 4WD: рекомендовано 2 плати — по каналу на мотор.
// З однією платою підключи лише передні пари пінів, а мотори кожного боку — паралельно.

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Скопіюй include/secrets.example.h у include/secrets.h і впиши Wi-Fi"
#endif

// L9110S: у кожного мотора два входи (IA, IB). Швидкість — ШІМ на одному вході, другий у нулі.
struct Motor {
  int ia;
  int ib;
  bool inverted;  // колесо крутиться не туди — постав true, дроти не чіпай
};

// Плата 1: лівий бік
constexpr Motor FRONT_LEFT{25, 26, false};   // A-IA, A-IB
constexpr Motor REAR_LEFT{27, 14, false};    // B-IA, B-IB
// Плата 2: правий бік
constexpr Motor FRONT_RIGHT{32, 33, false};  // A-IA, A-IB
constexpr Motor REAR_RIGHT{18, 19, false};   // B-IA, B-IB

// TT-мотори розраховані на 3–6 В. При 2×18650 (до 8.4 В) обмежуємо ШІМ:
// 6 В / 8.4 В ≈ 70%. Для 4×AA (6 В) можна поставити 100.
constexpr int MAX_DUTY_PERCENT = 70;

constexpr uint32_t MAX_MOVE_MS = 3000;
constexpr uint32_t DEFAULT_MOVE_MS = 500;

WebServer server(80);
uint32_t moveDeadline = 0;
bool moving = false;
String lastCommand = "none";

// speed: -100..100, знак задає напрям
void driveMotor(const Motor& m, int speed) {
  if (m.inverted) speed = -speed;
  speed = constrain(speed, -100, 100);
  int duty = map(abs(speed), 0, 100, 0, 255 * MAX_DUTY_PERCENT / 100);
  // Обидва входи завжди через analogWrite: на ESP32 змішувати його з digitalWrite на одному піні не можна.
  analogWrite(m.ia, speed > 0 ? duty : 0);
  analogWrite(m.ib, speed < 0 ? duty : 0);
}

void drive(int left, int right) {
  driveMotor(FRONT_LEFT, left);
  driveMotor(REAR_LEFT, left);
  driveMotor(FRONT_RIGHT, right);
  driveMotor(REAR_RIGHT, right);
}

void stopMotors() {
  drive(0, 0);
  moving = false;
}

void sendJson(int code, const String& body) {
  server.sendHeader("Cache-Control", "no-store");
  server.send(code, "application/json", body);
}

void handleMove() {
  String dir = server.arg("dir");
  uint32_t ms = server.hasArg("ms") ? server.arg("ms").toInt() : DEFAULT_MOVE_MS;
  int speed = server.hasArg("speed") ? server.arg("speed").toInt() : 60;
  ms = min(ms, MAX_MOVE_MS);
  speed = constrain(speed, 0, 100);

  if (dir == "forward") drive(speed, speed);
  else if (dir == "backward") drive(-speed, -speed);
  else if (dir == "left") drive(-speed, speed);   // поворот на місці
  else if (dir == "right") drive(speed, -speed);
  else {
    sendJson(400, "{\"ok\":false,\"error\":\"dir must be forward|backward|left|right\"}");
    return;
  }

  moving = ms > 0;
  moveDeadline = millis() + ms;
  lastCommand = dir;
  Serial.printf("move %s %ums speed %d\n", dir.c_str(), ms, speed);
  sendJson(200, "{\"ok\":true}");
}

void handleStop() {
  stopMotors();
  lastCommand = "stop";
  Serial.println("stop");
  sendJson(200, "{\"ok\":true}");
}

void handleStatus() {
  String ip = WiFi.getMode() == WIFI_AP ? WiFi.softAPIP().toString() : WiFi.localIP().toString();
  sendJson(200, String("{\"ok\":true,\"ip\":\"") + ip +
                    "\",\"rssi\":" + WiFi.RSSI() +
                    ",\"moving\":" + (moving ? "true" : "false") +
                    ",\"last\":\"" + lastCommand +
                    "\",\"uptime_s\":" + (millis() / 1000) + "}");
}

void connectWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setHostname("speedy-robot");
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.printf("Wi-Fi: підключаюсь до %s", WIFI_SSID);
  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
    delay(500);
    Serial.print('.');
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("Wi-Fi OK. Адреса для налаштувань Спіді: http://%s\n", WiFi.localIP().toString().c_str());
  } else {
    WiFi.mode(WIFI_AP);
    WiFi.softAP(FALLBACK_AP_SSID, FALLBACK_AP_PASS);
    Serial.printf("Не вдалося. Точка доступу %s, адреса http://%s\n",
                  FALLBACK_AP_SSID, WiFi.softAPIP().toString().c_str());
  }
  MDNS.begin("speedy-robot");
}

void setup() {
  Serial.begin(115200);
  stopMotors();

  connectWifi();

  server.on("/move", handleMove);
  server.on("/stop", handleStop);
  server.on("/status", handleStatus);
  server.onNotFound([] { sendJson(404, "{\"ok\":false,\"error\":\"not found\"}"); });
  server.begin();
}

void loop() {
  server.handleClient();

  if (moving && (int32_t)(millis() - moveDeadline) >= 0) {
    stopMotors();
    Serial.println("auto-stop");
  }

  // Втратили домашню мережу — зупинитись і перепідключитись.
  static uint32_t lastCheck = 0;
  if (WiFi.getMode() == WIFI_STA && millis() - lastCheck > 5000) {
    lastCheck = millis();
    if (WiFi.status() != WL_CONNECTED) {
      stopMotors();
      WiFi.reconnect();
    }
  }
}
