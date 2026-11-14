// Прошивка колісної платформи Спіді.
//
// HTTP-протокол (той самий, що в RobotPlatform.kt):
//   GET /move?dir=forward|backward|left|right&ms=0..3000&speed=0..100
//   GET /stop
//   GET /status  -> {"ok":true,...}
//
// Автопошук: UDP-порт 4210, на запит "SPEEDY_DISCOVER" плата відповідає "SPEEDY_ROBOT 80".
//
// Безпека: кожна команда руху має обмежену тривалість. Мотори зупиняє сама
// плата, навіть якщо телефон завис або зник Wi-Fi.
//
// Драйвер: L9110S. Одна плата (за замовчуванням): лівий бік на каналі A, правий на B,
// мотори кожного боку з'єднані паралельно. Дві плати — по каналу на кожен мотор (TWO_DRIVERS).

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <WiFiUdp.h>

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

// false — одна плата L9110S, мотори кожного боку паралельно; true — дві плати, по каналу на мотор.
constexpr bool TWO_DRIVERS = false;

// Плата 1 (єдина): канал A — лівий бік, канал B — правий бік
constexpr Motor LEFT{25, 26, false};    // A-IA, A-IB
constexpr Motor RIGHT{32, 33, false};   // B-IA, B-IB
// Плата 2 (лише при TWO_DRIVERS): задні мотори; тоді плата 1 крутить лише передні
constexpr Motor REAR_LEFT{27, 14, false};   // A-IA, A-IB
constexpr Motor REAR_RIGHT{18, 19, false};  // B-IA, B-IB

// TT-мотори розраховані на 3–6 В. При 2×18650 (до 8.4 В) обмежуємо ШІМ:
// 6 В / 8.4 В ≈ 70%. Для 4×AA (6 В) можна поставити 100.
constexpr int MAX_DUTY_PERCENT = 70;

constexpr uint32_t MAX_MOVE_MS = 3000;
constexpr uint32_t DEFAULT_MOVE_MS = 500;

// Плавний розгін: найбільший струм мотори тягнуть на старті, а з двома моторами на канал
// L9110S (~0.8 А) легко перевантажити. 0 -> 100% приблизно за 150 мс.
constexpr int RAMP_STEP = 7;
constexpr uint32_t RAMP_INTERVAL_MS = 10;

WebServer server(80);
WiFiUDP discovery;
constexpr uint16_t DISCOVERY_PORT = 4210;
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

int targetLeft = 0, targetRight = 0;
int currentLeft = 0, currentRight = 0;

void applyOutputs() {
  driveMotor(LEFT, currentLeft);
  driveMotor(RIGHT, currentRight);
  if (TWO_DRIVERS) {
    driveMotor(REAR_LEFT, currentLeft);
    driveMotor(REAR_RIGHT, currentRight);
  }
}

// Задає цільову швидкість; до неї мотори доходять плавно в rampMotors().
void drive(int left, int right) {
  targetLeft = left;
  targetRight = right;
}

int stepTowards(int current, int target) {
  if (current < target) return min(current + RAMP_STEP, target);
  if (current > target) return max(current - RAMP_STEP, target);
  return current;
}

void rampMotors() {
  static uint32_t last = 0;
  if (millis() - last < RAMP_INTERVAL_MS) return;
  last = millis();
  if (currentLeft == targetLeft && currentRight == targetRight) return;
  currentLeft = stepTowards(currentLeft, targetLeft);
  currentRight = stepTowards(currentRight, targetRight);
  applyOutputs();
}

// Зупинка — миттєва, без плавності: це команда безпеки.
void stopMotors() {
  targetLeft = targetRight = currentLeft = currentRight = 0;
  applyOutputs();
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

// Відповідь Спіді на пошук: так телефон дізнається IP плати без ручного введення.
void handleDiscovery() {
  int size = discovery.parsePacket();
  if (size <= 0) return;
  char buf[32] = {0};
  discovery.read(buf, sizeof(buf) - 1);
  if (strncmp(buf, "SPEEDY_DISCOVER", 15) != 0) return;
  discovery.beginPacket(discovery.remoteIP(), discovery.remotePort());
  discovery.print("SPEEDY_ROBOT 80");
  discovery.endPacket();
  Serial.printf("discovery from %s\n", discovery.remoteIP().toString().c_str());
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
  discovery.begin(DISCOVERY_PORT);
}

void loop() {
  server.handleClient();
  handleDiscovery();
  rampMotors();

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
