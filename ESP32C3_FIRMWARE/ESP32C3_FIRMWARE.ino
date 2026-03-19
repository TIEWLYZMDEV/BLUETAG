#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLEAdvertising.h>
#include <string>

#define TAG_NAME "BLUETAG"
#define BUZZER_PIN 2
#define BATTERY_PIN 3

#define SERVICE_UUID        "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID "abcd1234-5678-90ab-cdef-1234567890ab"

BLEServer* pServer = NULL;
bool deviceConnected = false;
unsigned long lastBatteryUpdate = 0;

void buzzerOn() {
    digitalWrite(BUZZER_PIN, LOW);
}

void buzzerOff() {
    digitalWrite(BUZZER_PIN, HIGH);
}

uint8_t getBatteryPercent() {
    int rawADC = analogRead(BATTERY_PIN);

    float voltage = (rawADC / 4095.0) * 3.3 * 2.0;

    int percent = (int)((voltage - 3.3) / (4.2 - 3.3) * 100);

    // จำกัดค่าไม่ให้เกิน 100 หรือต่ำกว่า 0
    if (percent > 100) percent = 100;
    if (percent < 0) percent = 0;

    return (uint8_t)percent;
}

void updateAdvertisingData() {
    uint8_t battLevel = getBatteryPercent();

    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();

    BLEAdvertisementData advData;
    advData.setName(TAG_NAME);
    advData.setFlags(0x06);

    std::string mData = "";
    mData += (char)0xFF;
    mData += (char)0xFF;
    mData += (char)battLevel;

    advData.setManufacturerData(mData);
    pAdvertising->setAdvertisementData(advData);
}

class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        deviceConnected = true;
        Serial.println("App Connected!");
    }

    void onDisconnect(BLEServer* pServer) override {
        deviceConnected = false;
        Serial.println("App Disconnected. Restarting advertising...");
        pServer->getAdvertising()->start();
    }
};

void setup() {
    Serial.begin(115200);
    delay(300);

    pinMode(BUZZER_PIN, OUTPUT);
    buzzerOff();

    buzzerOn(); delay(120); buzzerOff();

    analogReadResolution(12);

    BLEDevice::init(TAG_NAME);
    BLEDevice::setPower(ESP_PWR_LVL_P3);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);
    BLECharacteristic* pCharacteristic = pService->createCharacteristic(
            CHARACTERISTIC_UUID,
            BLECharacteristic::PROPERTY_READ
    );
    pCharacteristic->setValue("Tag Ready");
    pService->start();

    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinInterval(320);
    pAdvertising->setMaxInterval(640);

    updateAdvertisingData(); // อัปเดตข้อมูลแบตเตอรี่ครั้งแรก
    pAdvertising->start();

    Serial.println("BlueTag is ready and advertising...");
}

void loop() {
    if (deviceConnected) {
        buzzerOn();
        delay(80);
        buzzerOff();
        delay(150);
    } else {
        if (millis() - lastBatteryUpdate > 5000) {
            updateAdvertisingData();
            lastBatteryUpdate = millis();
            Serial.print("Current Battery: ");
            Serial.print(getBatteryPercent());
            Serial.println("%");
        }
        delay(200);
    }
}