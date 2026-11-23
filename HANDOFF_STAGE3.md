\# Handoff: Stage 3 (BLE), до GATT-сервера



\## Статус: 

\- BleScanner + AndroidBleScanner + BleAdvertiser + AndroidBleAdvertiser готовы (169 тестов, BUILD SUCCESSFUL).

\- Реклама: 16-bit UUID (0xFFF0), payload 19 байт.

\- Проверки Location на API 24-30 есть.

\- Следующий шаг: BleGattServer + BleGattClient.



\## Что нужно напомнить ИИ:

1\. onNotificationSent (а не onDescriptorWrite) для подтверждения индикаций.

2\. GATT-операции на клиенте через Mutex (защита от GATT\_ERROR).

3\. На API 33+ переопределить новые overload'ы (onCharacteristicChanged с value, writeCharacteristic с value).

4\. Если onMtuChanged не пришел за 5 секунд — fallback на 23.



\## Архитектура:

\- BleGattServer: GATT-сервис с явным CCCD (0x2902), очередь индикаций, per-connection Mutex.

\- BleGattClient: connect → discover → requestMtu → enableIndications → write.

