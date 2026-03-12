#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLEAdvertising.h>

#define TAG_NAME "BLUETAG"
#define BUZZER_PIN 2

#define SERVICE_UUID        "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID "abcd1234-5678-90ab-cdef-1234567890ab"

BLEServer* pServer = NULL;
bool deviceConnected = false;

void buzzerOn() {
  digitalWrite(BUZZER_PIN, LOW);   
}

void buzzerOff() {
  digitalWrite(BUZZER_PIN, HIGH);  
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

  buzzerOn();
  delay(120);
  buzzerOff();

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

  pAdvertising->setMinInterval(320); 
  pAdvertising->setMaxInterval(640);

  BLEAdvertisementData advData;
  advData.setName(TAG_NAME);
  pAdvertising->setAdvertisementData(advData);
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);

  pAdvertising->start();
  Serial.println("Advertising started...");
}

void loop() {
  if (deviceConnected) {
    buzzerOn();
    delay(80);
    buzzerOff();
    delay(150);
  } else {
    delay(200); 
  }
}