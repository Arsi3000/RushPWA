# APK из HTML — сборка на GitHub, без своего сервера

Полностью автономный конвейер: кладёшь `index.html` в репозиторий — GitHub сам собирает APK
и выкладывает в Releases. Никаких серверов, ноутбуков и посредников.

**Постоянная ссылка на последнюю сборку:**

```
https://github.com/Arsi3000/RushPWA/releases/latest/download/skin-rush.apk
```

## Что здесь лежит

```
index.html                     ← сюда кладёшь игру (свою версию)
app/AndroidManifest.xml        ← обёртка: пакет com.skinrush.game
app/src/.../MainActivity.java  ← WebView, localStorage, диалоги, портрет
app/res/                       ← иконки и стили
tools/build-apk.sh             ← та же цепочка, что на apkbuild:
                                 aapt2 → javac → d8 → zip(assets) → zipalign → apksigner
.github/workflows/apk.yml      ← сборка на серверах GitHub
```

Gradle не нужен. На раннере `ubuntu-24.04` Android SDK 34 уже установлен.

## Первая настройка (один раз, ~5 минут)

1. Залить в репозиторий всё содержимое этой папки (можно поверх `RushPWA`).
2. Положить ключ подписи в секреты. Это тот же ключ, что у установленных APK —
   **другой ключ заставит игроков удалять приложение**.

   Windows (PowerShell, в папке с ключом):

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("skin-rush-keystore.jks")) | Set-Clipboard
   ```

   macOS / Linux:

   ```bash
   base64 -w0 skin-rush-keystore.jks | pbcopy    # или xclip -selection clipboard
   ```

3. GitHub → репозиторий → **Settings** → **Secrets and variables** → **Actions** →
   **New repository secret**. Добавить два:

   | Имя | Значение |
   |---|---|
   | `KEYSTORE_B64` | строка из буфера (весь `.jks` в base64) |
   | `KS_PASS` | пароль ключа |

   Необязательный третий: `KS_ALIAS` (по умолчанию `skinrush`).

4. Всё. Дальше сборка запускается сама.

## Как собирать

- **Автоматически:** закоммитил `index.html` (или поменял `app/**`) → вкладка **Actions** →
  «Сборка APK» → через 3–4 минуты релиз `v<версия>` с файлом `skin-rush-<версия>.apk`.
- **Вручную:** **Actions** → **Сборка APK** → **Run workflow**. Можно указать путь к HTML,
  версию и versionCode, если надо собрать не то, что лежит в `index.html`.

## Версия

Берётся из самого HTML — строка `const VERSION = '4.4.6';`

```
versionName = 4.4.6
versionCode = 4004006      (major*1000000 + minor*1000 + patch)
```

Старые сборки шли с номерами вида `4490`, поэтому 4004006 их честно перекрывает —
Android увидит обновление. **Поднял версию в HTML — Android даст обновиться поверх.**

## Что проверить перед публикацией

- **Иконки и модели грузятся из интернета.** В игре прописано
  `const WEAPON_IMG_BASE = 'https://arsi3000.github.io/RushPWA/'`.
  Без сети APK покажет заглушки вместо стволов. Если нужен офлайн — картинки (`.png`, ~2 МБ)
  надо вшить в `app/assets/` и заменить базу на относительный путь.
- **Проверить на телефоне.** У GitHub нет эмулятора, как и у машины сборки: проверяются только
  подпись, `versionCode`, наличие `classes.dex` и совпадение md5 HTML внутри APK с исходником.

## Проверка сборки (что смотреть в логах)

```
Signer #1 certificate SHA-1 digest: 39c3206b287694e661047dcd348d0863dac0b79a
package: name='com.skinrush.game' versionCode='4004006' versionName='4.4.6'
HTML в APK: <md5>      ← должен совпасть с
HTML исходный: <md5>
```

SHA-1 ключа всегда должен быть `39c3206b…0b79a` — иначе подпись не та.

## iPhone

`.apk` на айфон не ставится вообще. Для iPhone тот же HTML работает как PWA:
Safari → «Поделиться» → «На экран "Домой"», офлайн через service worker.
Для этого игра должна открываться по https — то есть `RushPWA` с `index.html`, `sw.js`
и `manifest.json` в корне.

## Если Actions недоступны

Запасные пути, оба без моего компьютера:

1. **PWABuilder** (pwabuilder.com, Microsoft) — вводишь адрес сайта, получаешь пакет для Android.
   Это TWA-обёртка: игра грузится из интернета, офлайна нет, ключ подписи выдаёт сервис.
2. **Сборка на своём ПК**: тот же `tools/build-apk.sh` + JDK 17 + Android SDK
   (cmdline-tools + build-tools 34 + platform android-34). Работает без сети, если SDK уже скачан.

## Если что-то пошло не так

| Ошибка | Причина |
|---|---|
| `Нет секрета KEYSTORE_B64` | не добавлен секрет — шаг 3 |
| `keystore password was incorrect` | `KS_PASS` не тот |
| `APK signature verification` при установке | собран другим ключом, а не `skinrush.jks` |
| `App not installed` на телефоне | `versionCode` не больше установленного — подними `VERSION` в HTML |
| пустой экран в приложении | HTML внутри APK не тот: сверь md5 в логе |
