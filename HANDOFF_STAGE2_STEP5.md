Handoff: Stage 2, Шаг 5 завершён

Созданные файлы

app/src/main/java/com/sunshine/mesh/core/crypto/e2e/

&#x20; EnvelopeCipher.kt      AES-256-GCM, per-session nonce guard

&#x20; E2ESession.kt          sealText(), openFragment(), close()



app/src/test/java/com/sunshine/mesh/core/crypto/e2e/

&#x20; EnvelopeCipherTest.kt          5 тестов

&#x20; E2ESessionTest.kt              7 тестов

&#x20; E2ESessionRejectionTest.kt     6 тестов

&#x20; E2ETestSupport.kt              общий тестовый helper

Изменённый файл

app/src/main/java/com/sunshine/mesh/core/crypto/e2e/E2EException.kt

&#x20; + NONCE\_REUSE

&#x20; + ENCRYPTION\_FAILED

&#x20; + DECRYPTION\_FAILED

Что реализовано

EnvelopeCipher — AES-256-GCM с проверкой nonce на повтор в рамках сессии; повтор → NONCE\_REUSE.

E2ESession.sealText(messageId, text) — UTF-8 → фрагменты до 446 байт → N конвертов по 512 байт, до 255 частей.

E2ESession.openFragment(packet) — проверки в порядке: тип → recipient → sender → GCM → unpad.

FragmentAssembler остаётся снаружи; E2ESession не собирает сообщения.

close() идемпотентен, зануляет ключи и nonce-набор; после него sealText/openFragment бросают IllegalStateException.

E2ESession не импортирует PeerRepository, PeerTrustGate или TrustState.

ID в конверте — key-derived из HkdfSession, не label из БД.

Компиляция и тесты

.\\gradlew.bat --no-daemon testDebugUnitTest



BUILD SUCCESSFUL in 43s

tests=120 failures=0 errors=0 suites=19

Новые тесты: 18, все зелёные. Полный unit-suite: 120 тестов, 0 падений, 19 suites.



Инструментальный миграционный тест migration1To2KeepsDataAndResetsTrust ранее прошёл на реальном устройстве; в Шаге 5 его не перезапускал — изменения его не затрагивают.



Состояние Git

Изменения не закоммичены:



M  app/src/main/java/com/sunshine/mesh/core/crypto/e2e/E2EException.kt

?? app/src/main/java/com/sunshine/mesh/core/crypto/e2e/E2ESession.kt

?? app/src/main/java/com/sunshine/mesh/core/crypto/e2e/EnvelopeCipher.kt

?? app/src/test/java/com/sunshine/mesh/core/crypto/e2e/E2ESessionTest.kt

?? app/src/test/java/com/sunshine/mesh/core/crypto/e2e/E2ESessionRejectionTest.kt

?? app/src/test/java/com/sunshine/mesh/core/crypto/e2e/EnvelopeCipherTest.kt

?? app/src/test/java/com/sunshine/mesh/core/crypto/e2e/E2ETestSupport.kt

README не изменялся. Следующая сессия — Шаг 6: README и документация Stage 2.

