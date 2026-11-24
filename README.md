# GRANULAR//LAB — гранулярный синтезатор для Android 4.1.x

Ориентиры: Tasty Chips GR-1, Bastl microGranny, nanobox.
Нативный Java-движок (AudioTrack, 44100 Hz, 32 гранул-голоса), UI — ES5 WebView.
Без AndroidX и внешних зависимостей. minSdk 16, targetSdk 22.

## Сборка APK через GitHub
1. Распакуй ZIP — получишь папку `GranularLab/` (26 файлов).
2. github.com/new → пустой репозиторий `granular-lab` (без README).
3. `uploading an existing file` → перетащи ВСЁ содержимое папки, включая `.github` → Commit.
   Если `.github` не залилась: Add file → Create new file → `.github/workflows/build.yml` → вставь workflow.
4. Actions → `Build GranularLab APK` → зелёная галка (3–5 мин).
5. Artifacts → `GranularLab-debug-apk` → `app-debug.apk` на планшет.
6. Настройки → Безопасность → Неизвестные источники ✓ → установи.

## Управление
- Волна: тяни пальцем — POSITION. Точки — активные гранулы (возраст/панорама).
- XY: X — позиция, Y — размер гранулы.
- RUN — транспорт, HOLD — лэтч (звучит без клавиш). Выключи HOLD и играй клавиатурой/MIDI.
- Клавиатура: 2 октавы, мультитач, глиссандо, сдвиг октав.
- DEMO — 4 синтезированных банка. LOAD — браузер SD. REC — микрофон до 30 с.
- EXPORT — офлайн-рендер 8 с со всеми FX в /sdcard/Music/GranularLab/.
- Пресеты: 6 фабричных + свои, RND, INIT.

## USB MIDI (OTG)
- MIDI → SCAN → CONNECT → разреши доступ Android.
- CC по умолчанию: 1 spread, 7 master, 10 pan, 11 density, 74 cutoff, 71 res, 91 verb, 93 delay, 64 hold.
- CC LEARN: MIDI → LEARN → коснись регулятора → двинь ручку на клаве. Карта сохраняется.
- MIDI clock → BPM + SYNC дилея. START/STOP → RUN. Pitch bend ±2 st.
- BLE-MIDI на Android 4.1 невозможен (BLE с 4.3) — только USB.

## Примечания
- Латентность старых планшетов ~80–120 мс — нормально для пэдовых текстур.
- Back = фон, звук продолжается. Убери уведомление — движок остановится.
- Сэмплы длиннее 60 с обрезаются, всё нормализуется до пика 0.89.
