# Sunshine

Mesh-мессенджер для Android: сообщения передаются между устройствами рядом, без интернета, вышек и аккаунтов.

> **До использования:** MVP работает по модели TOFU, без forward secrecy и без подписей отправителя. Подробности и остальные ограничения — ниже.

## Статус: Stage 6 (UI/UX) завершён. Ведётся активный багфикс BLE-транспорта.

Сделано:

- каркас проекта на Kotlin, Compose Material 3, тёмная тема;
- Room v3: `peers`, `messages`, `seen_ids`, `pending_packets`, поля доверия, индексы, экспорт схемы, миграции 1→2→3;
- генерация X25519 identity, `peerId = SHA-256(publicKey)[0..8]`;
- шифрование приватного ключа ключом Android Keystore и хранение в Preferences DataStore;
- онбординг с ником и экран профиля с отпечатком ключа;
- runtime-разрешения для поиска устройств на API 24–32 и 33+;
- политика BLE-таймаута 10 секунд с принудительным закрытием зависшего соединения;
- E2E-ядро: X25519 ECDH → HKDF-SHA256 → два направленных ключа AES-256-GCM;
- конверты фиксированного размера 512 байт, AAD 38 байт, padding до 446 байт, фрагментация до 255 частей;
- `E2ESession.sealText/openFragment/close`, `EnvelopeCipher` с защитой от повторного nonce, `FragmentAssembler`;
- TOFU, `KEY_CHANGED` и Safety Number в ядре, `PeerTrustGate` до открытия сессии;
- BLE-транспорт: непрозрачные 512-байтные пакеты, `Transport`, GATT server/client, chunking и reassembly с таймаутом 10 с;
- реклама 19 байт (16-bit UUID `0xFFF0` + 8-byte peerId), `ScanFilter` по UUID, проверки Location на API 24–30;
- роль по peerId: меньший подключается (central), больший принимает (peripheral); лимит 4 линка;
- INDICATE с подтверждением `onNotificationSent`, per-connection Mutex, MTU fallback 23, проверка версии HELLO/HELLO_ACK;
- `TransportService` (foreground service `connectedDevice`) и фабрика транспорта в `AppGraph`;
- Wi-Fi Direct транспорт: DNS-SD обмен peerId/портом, роли GO/клиент по peerId, TCP с length-prefix 4 байта и handshake 8 байт, таймауты 10 с, принудительный `removeGroup` при close;
- Mesh-логика: заголовок 34 байта (version, hop-TTL, msgId, senderId, recipientId) поверх конверта 512 байт (пакет 546 байт), flooding всем линкам кроме источника, дедупликация LRU 4096 по (msgId, partIndex), store-and-forward на 72 часа с удалением истёкших без попытки расшифровки;
- Stage 5: `MeshCoordinator` связывает E2E, доверие, Room и mesh: `KEY_EXCHANGE` обрабатывается до расшифровки, сессии кэшируются по `PeerId` под `Mutex`, входящие `TEXT`/`RECEIPT` сохраняются, Safety Number и `KEY_CHANGED` отдаются событиями;
- исходящий outbox: сообщение сохраняется как `PENDING` до шифрования, готовые 546-байтные пакеты лежат в `messages.packet`, отправка повторяется при появлении линка и после `acceptTrust`; статусы `PENDING → SENT → DELIVERED`;
- `TransportHub` объединяет BLE и Wi-Fi Direct; `TransportService` запускает оба транспорта, `MeshEngine` и `MeshCoordinator` через `AppGraph`;
- **Stage 6: UI в стиле Amber Glow (тёмная тема, моноширинные шрифты, янтарный акцент).** Реализованы экраны: список чатов, чат, профиль (Safety Number, управление доверием), сеть (статус, список пиров, разрешения), справка, проверка обновлений через GitHub API. Настроена Release-сборка с R8 и ProGuard.

**Важно:** сообщения передаются end-to-end через оба транспорта. `DELIVERED` работает best-effort: квитанции не персистятся и не повторяются. `KEY_CHANGED` обнаруживается и показывается, но новый ключ автоматически не принимается (требуется ручное подтверждение).

### 🛠 Активный багфикс (Stafe 6.5)
Идёт работа над исправлением критических багов BLE-транспорта, выявленных при аудите (GLM 5.4):
- **P0 (Исправлено):** Краш при обрыве связи до HELLO и отсутствие перезапуска рекламы после подключения.
- **P1 (В работе):** Обработка ошибок скана/рекламы с ретраями, backpressure в очередях линков, повторная отправка `SENT` без квитанции, отключение автозапуска Wi-Fi Direct.

### Транспорты: покрытие тестами

Код BLE и Wi-Fi Direct покрыт unit-тестами с fake-объектами и loopback-сокетами. Проверка на реальных устройствах выявила необходимость правок (см. «Активный багфикс»). После исправлений будет проведён повторный тест на двух телефонах.

### План этапов

| Этап | Содержание | Статус |
| --- | --- | --- |
| Stage 1 | Скелет проекта, Room, Identity | Сделано |
| Stage 2 | E2E-шифрование, фрагментация, TOFU | Сделано |
| Stage 3 | BLE-транспорт | Сделано |
| Stage 4 | Wi-Fi Direct транспорт и Mesh-логика | Сделано |
| Stage 5 | Интеграция (MeshCoordinator, E2E + Transport + Room) | Сделано |
| Stage 6 | UI/UX (Amber Glow, все экраны), Release-сборка | Сделано |
| **Stage 6.5** | **Багфикс BLE-транспорта (P0/P1) и стабилизация** | **В работе** |
| Stage 7 | Оптимизация, батарея, финальный релиз | |

## Что такое E2E в Sunshine

(Раздел без изменений...)

### Ключи сессии

- Статический X25519 ECDH на identity-ключах.
- HKDF-SHA256: salt = SHA-256 от двух публичных ключей, отсортированных побайтово; info = `sunshine/e2e/v1`.
- 64 байта HKDF делятся на два направленных ключа AES-256-GCM. Первые 32 байта принадлежат направлению «меньший ключ → больший», вторые 32 — обратному. Это делает схему независимой от ролей отправителя и получателя.
- `peerId` в конверте выводится из публичного ключа, а не берётся из UI или БД.
- Ключи сессии живут только в памяти и зануляются при `E2ESession.close()`.

### Конверт 512 байт и AAD 38 байт

| Поле | Байт | Примечание |
| --- | ---: | --- |
| `version` | 1 | текущая версия формата |
| `type` | 1 | `TEXT`, `KEY_EXCHANGE`, `RECEIPT` |
| `msgId` | 16 | идентификатор сообщения |
| `senderId` | 8 | key-derived `peerId` отправителя |
| `recipientId` | 8 | key-derived `peerId` получателя |
| `paddingLength` | 2 | 0..446 |
| `partIndex` | 1 | 0..N-1 |
| `totalParts` | 1 | 1..255 |
| `nonce` | 12 | GCM |
| `ciphertext` | 446 | всегда фиксированный |
| `tag` | 16 | GCM |
| **Итого** | **512** | |

Первые 38 байт — открытый заголовок. Это же и есть AAD: GCM аутентифицирует его, но не шифрует. Отдельно AAD не передаётся.

TTL в AAD не входит: реле должны уметь уменьшать его, не ломая tag. Публичные ключи в AAD тоже не входят — они привязаны через HKDF.

Пакет неверного размера отбрасывается до разбора и расшифровки.

*(Остальные разделы — Padding, Фрагментация, Приём и проверки, Доверие, Безопасность и логирование, Ограничения MVP, Криптография, Разрешения, Сборка и тесты, Структура — остаются без изменений, но я добавил в таблицу криптографии `type` и обновил статус).*

### Padding 446 байт

- Полезная нагрузка фрагмента — до 446 байт.
- Открытый текст дополняется случайными байтами ровно до 446 байт.
- Фактическая длина padding лежит в AAD (`paddingLength`, 2 байта). Два байта нужны потому, что 446 не помещается в один байт.
- Шифротекст всегда 446 байт, пакет всегда 512 байт. Размер одного пакета не выдаёт длину фрагмента; количество частей видно по `totalParts`.
- Padding снимается только после успешной проверки GCM tag.

### Фрагментация: до 255 частей

- Одна часть — до 446 байт полезной нагрузки.
- `partIndex` — от 0 до N-1, `totalParts` — от 1 до 255.
- Максимальный размер сообщения: **255 × 446 = 113 730 байт UTF-8**.
- Пустое сообщение кодируется одной частью, целиком состоящей из padding.
- `FragmentAssembler` остаётся снаружи `E2ESession` и собирает сообщение по `(senderId, msgId)`.
- Части могут приходить не по порядку. Идентичные дубликаты игнорируются; конфликтующий дубликат или изменение метаданных отбрасывает всю сборку.
- Незавершённые сборки ограничены TTL, количеством и объёмом буфера; при очистке данные зануляются.
- UTF-8 декодируется только после того, как assembler вернул полное сообщение.

### Приём и проверки

Порядок проверок в `E2ESession.openFragment`:

1. размер пакета, версия и тип;
2. `recipientId` совпадает с локальным;
3. `senderId` совпадает с ожидаемым пиром;
4. GCM tag и расшифровка;
5. снятие padding.

Ошибки сообщаются кодами `E2EException.Code`, без деталей, раскрывающих ключевой материал.

`EnvelopeCipher` хранит набор уже использованных nonce в рамках сессии: повтор при шифровании даёт `NONCE_REUSE`. Это защита от программной ошибки, а не замена случайным 96-битным nonce.

`close()` идемпотентен: зануляет ключи и набор nonce. После него `sealText` и `openFragment` бросают `IllegalStateException`.

`E2ESession` не знает о `TrustState`, `PeerRepository` и БД. Решение о доверии принимается до создания сессии.

## Доверие: TOFU, KEY_CHANGED, Safety Number

### TOFU

Первый полученный публичный ключ пира считается настоящим после подтверждения пользователем. До подтверждения `PeerTrustGate` возвращает `TrustRequired` с Safety Number; экран подтверждения появится на этапе UI.

| Состояние | Значение |
| --- | --- |
| `UNKNOWN` | ключ есть, решения пользователя ещё нет |
| `TOFU_ACCEPTED` | первый ключ принят пользователем |
| `VERIFIED` | ключ сверен вручную по Safety Number |
| `KEY_CHANGED` | для известного `peerId` пришёл другой ключ |

`peerId` — это label для поиска пира в локальной БД, а не доказательство ключа. Доверие привязано к публичному ключу.

### KEY_CHANGED

Если для известного `peerId` приходит другой публичный ключ, состояние становится `KEY_CHANGED`. Сессия не открывается: ядро возвращает `KeyChanged` с новым Safety Number, а пользователь должен подтвердить ключ заново. Экран предупреждения появится на этапе UI.

При миграции Room 1→2 существующие пиры намеренно переводятся в `UNKNOWN`: молча наследовать доверие от сборки без проверки ключей нельзя.

### Safety Number

- SHA-256 от двух публичных ключей, отсортированных побайтово.
- **6 групп по 5 цифр** (30 цифр), например `12345-67890-...`.
- Обе стороны видят одинаковый код, поэтому его можно сверить голосом или глазами.
- Экран ручной сверки появится на этапе UI.

## Безопасность и логирование

Разрешено логировать только:

- `msgId`;
- `peerId`;
- размеры (пакетов, фрагментов, сообщений);
- коды ошибок.

Запрещено логировать:

- ключи, shared secret и производные;
- plaintext и расшифрованные фрагменты;
- nonce;
- содержимое конвертов.

В криптокоде нет вызовов `android.util.Log`. `toString()` ключевых объектов не печатает материал: `HkdfSession` и `SessionKeys` показывают только идентификаторы и размеры, `PaddedPayload` — размер и длину padding.

`E2ESession.close()` зануляет ключи и nonce-набор. `PaddedPayload.close()` и промежуточные буферы зануляют открытый текст после использования.

## Ограничения MVP

Прочитать до того, как доверять приложению секреты.

- **TOFU (trust on first use).** Первый полученный публичный ключ пира считается настоящим после подтверждения. Safety Number и `KEY_CHANGED` реализованы в ядре; экран ручной сверки появится на этапе UI. Злоумышленник, который первым передаст свой ключ, может выдать себя за собеседника.
- **`peerId` — это label, а не доказательство ключа.** Восемь байт используются только для поиска пира в локальной БД. Они не доказывают, что публичный ключ хешируется в этот `peerId`. Защита от подмены ключа — `KEY_CHANGED` и ручная сверка Safety Number.
- **Нет forward secrecy.** Ключи X25519 долгоживущие. Компрометация статического приватного ключа и записанного трафика раскрывает переписку, включая старые сообщения.
- **Нет подписей отправителя.** Сообщение защищено парным ключом AES-GCM, но третьей стороне нечем доказать авторство. Ник не подтверждён криптографически. Подмена ключа при первом контакте возможна.
- **Store-and-forward ограничен.** Пакеты, адресованные мне, которые я пока не могу расшифровать (нет ключа пира или ожидает доверия), хранятся в `pending_packets` до 72 часов; по истечении срока удаляются без повторной попытки расшифровки. Чужие транзитные пакеты не сохраняются. Собственные исходящие хранятся в `messages.packet` и повторяются при появлении линка.
- **`DELIVERED` — best-effort.** Квитанции не персистятся и не ставятся в очередь повторной отправки. Если квитанция потерялась, сообщение останется в `SENT`; доставка не гарантируется.
- **`KEY_CHANGED` пока не принимается.** Coordinator обнаруживает смену ключа и отдаёт событие с новым Safety Number, но принять новый ключ можно будет только после появления UI в Stage 6.
- **Метаданные видны.** Радиососеди и реле видят `peerId` отправителя и получателя, размер, время и TTL пакета, а также `totalParts`. Содержимое зашифровано.
- **Реле не доверенное.** Любой узел может отбросить, задержать или переслать пакет. Дедупликация не даёт повторно обработать один `msgId`, но не гарантирует доставку.
- **Нет восстановления профиля.** Потеря устройства означает потерю ключа и истории. Резервного копирования нет.
- **MVP не проходил аудит.** Не использовать для действительно чувствительных данных.

## Криптография

Без самописной криптографии.

| Задача | Реализация |
| --- | --- |
| X25519 | BouncyCastle `org.bouncycastle:bcprov-jdk18on:1.87`, `X25519PrivateKeyParameters`, `X25519PublicKeyParameters`, `X25519Agreement` |
| HKDF | BouncyCastle `HKDFBytesGenerator` + `HKDFParameters`, SHA-256; salt = SHA-256 отсортированных публичных ключей, info `sunshine/e2e/v1` |
| AES-256-GCM | JCA `AES/GCM/NoPadding`, 128-битный tag, 12-байтовый nonce, per-session nonce guard |
| Safety Number | SHA-256 по отсортированным публичным ключам, 6 групп по 5 цифр |
| Keystore | провайдер `AndroidKeyStore`, AES-256-GCM, non-exportable |

### Где лежит ключ

- AES-ключ для запечатывания identity: `AndroidKeyStore`, alias `sunshine.identity.v1`. Ключ создаётся внутри Keystore и не покидает его; приложение не может прочитать его байты. Android хранит ключевой материал в системном keystore (не в файлах приложения).
- Запечатанный X25519 приватный ключ: Preferences DataStore, файл `files/datastore/profile.preferences_pb`. Формат sealed-значения: версия (1 байт) + IV (12 байт) + ciphertext + GCM tag. AAD: `sunshine/identity/v1`.
- X25519 не поддерживается Android Keystore на minSdk 24, поэтому сам приватный ключ — программный; Keystore защищает только его хранение.
- Ключи сообщений выводятся через HKDF для каждой сессии и живут только в памяти. Они не хранятся в Keystore, отдельного alias `sunshine.messages.v1` нет. `E2ESession.close()` зануляет их.
- Метаданные сообщений остаются открытыми, чтобы работали индексы Room.

## Разрешения

- API 24–32: `ACCESS_FINE_LOCATION` и `ACCESS_COARSE_LOCATION` запрашиваются в runtime парой (в манифесте ограничены `maxSdkVersion="32"`): Android 12+ требует оба разрешения.
- API 31+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` и `BLUETOOTH_ADVERTISE`; для сканирования указан `neverForLocation`.
- API 33+: `NEARBY_WIFI_DEVICES` с `neverForLocation`, `POST_NOTIFICATIONS` для уведомления сервиса.
- Wi-Fi Direct использует те же разрешения, что и поиск: FINE_LOCATION на API 24–32 (плюс включённая геолокация), `NEARBY_WIFI_DEVICES` на 33+.
- API 34+: `FOREGROUND_SERVICE_CONNECTED_DEVICE` для foreground service типа `connectedDevice`.
- Кнопка запроса находится на экране профиля; без разрешений поиск устройств не запускается.
- `TransportService` объявлен в манифесте с `foregroundServiceType="connectedDevice"` и `exported="false"`.

## Сборка и тесты

Требования: JDK 17, Android SDK 36, Gradle 8.13.

```bash
.\gradlew testDebugUnitTest assembleDebug lintDebug
```

Gradle wrapper включён в репозиторий.

252 unit-теста в 35 suites покрывают E2E, доверие, Room, runtime-разрешения, ник, `peerId`, X25519, BLE-таймаут, кодек кадров, сборщик пакетов, рекламу, GATT-линки, `BleTransport`, mesh-заголовок, LRU-дедупликацию, `MeshEngine`, Wi-Fi Direct framing, `WifiDirectTransport`, `TransportHub`, outbox и `MeshCoordinator`. Room-схема экспортируется в `app/schemas/`; `lintDebug` проходит без ошибок.

Инструментальный тест миграции `migration1To2KeepsDataAndResetsTrust` ранее прошёл на реальном устройстве. В Шаге 5 он не перезапускался: изменения его не затрагивают.

## Структура

```
app/src/main/java/com/sunshine/mesh/
  core/crypto/       X25519, Keystore-обёртка, SafetyNumber, PublicKeyOrder
  core/crypto/e2e/   HKDF-сессия, конверт, AES-GCM, E2ESession, KeyExchangeCodec, ReceiptPayload
  core/ids/          PeerId, MessageId
  core/net/          таймаут соединения, политика mesh
  core/permissions/  runtime-разрешения
  core/transport/    Transport, TransportHub, BLE codec/assembler, GATT, links, BleTransport
  core/transport/wifidirect/  DNS-SD, группы, TCP framing, WifiDirectTransport
  core/mesh/         MeshHeader, SeenCache, MeshEngine, MeshStore, OutboxCodec
  coordinator/       MeshCoordinator, MeshEvent, SendTextResult, фабрика
  data/local/        Room: entity, dao, database, migrations
  data/mesh/         RoomMeshStore (store-and-forward)
  data/message/      MessageStore, RoomMessageStore (история и outbox)
  data/peer/         PeerRepository, PeerTrustGate, PeerKeyStore
  data/profile/      DataStore, ProfileRepository, IdentityRepository
  domain/message/    FragmentAssembler
  domain/model/      Profile, TrustState, направления и статусы сообщений
  domain/validation/ Nickname
  service/           TransportService (foreground service connectedDevice)
  ui/                onboarding, profile, permissions, theme
```

DI ручной: `AppGraph`, без Hilt. Миграции Room добавляются в `SunshineDatabase.MIGRATIONS`; destructive fallback не используется.
