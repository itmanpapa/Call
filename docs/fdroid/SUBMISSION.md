# Публикация CallGuard в F-Droid и IzzyOnDroid

Что уже сделано в репозитории:

* два варианта сборки (`app/build.gradle`): `github` — наши релизы со встроенным обновлением,
  `fdroid` — без самообновления (нет проверки обновлений, загрузки APK, пунктов меню и
  разрешения `REQUEST_INSTALL_PACKAGES`, см. `app/src/fdroid/AndroidManifest.xml`);
* `release.yml` прикладывает к релизу оба APK: `callguard-vX.Y.Z.apk` (`github`) и
  `callguard_fdroid-vX.Y.Z.apk` (`fdroid`). Встроенный апдейтер вариант `fdroid` никогда не берёт;
* описание для магазинов: `fastlane/metadata/android/` (`en-US`, `de-DE`, `ru`), иконка,
  баннер и скриншоты — в `en-US/images/` (для остальных языков F-Droid берёт их оттуда же);
* черновик метаданных F-Droid: [`de.itmanpapa.callblocker.yml`](de.itmanpapa.callblocker.yml);
* в APK не попадает зашифрованный Google блок «Dependency metadata»
  (`dependenciesInfo { includeInApk = false }`), его не пропускает сканер F-Droid.

## 0. Подготовка (обязательно до подачи)

1. **Выпустить версию 0.13.0.** В теге `v0.12.0` ещё нет варианта `fdroid` — F-Droid соберёт
   только тег, где он есть. В `app/build.gradle` поставить `versionName "0.13.0"`,
   `versionCode 13000` и `changelogs/13000.txt` (en-US, de-DE, ru) уже готовы — осталось выпустить
   релиз как обычно (тег `v0.13.0`). Проверить, что в релизе два APK.
   Если версия будет другой — поправить `versionName`/`versionCode`/`commit` в yml.
2. ~~Удалить старые описания YACB~~ — сделано: в `fastlane/metadata/android/` остались только
   en-US, de-DE и ru; остальные языки в F-Droid получат английский текст.
3. **Иконка.** ✅ Своя иконка (кольцо с трубкой и зелёной точкой), `en-US/images/icon.png` и
   `featureGraphic.png` перерисованы.
4. **Пожертвования.** ✅ Ko-fi (`donation_url` в `app/src/main/res/values/donation.xml`, `ko_fi:` в
   `.github/FUNDING.yml`, README, `Donate:` в yml) и Bitcoin (`donation_btc_address` там же, README,
   `Bitcoin:` в yml). F-Droid принимает только реквизиты, которые разработчик сам опубликовал в
   репозитории (FUNDING.yml/README) — при смене адреса менять во всех этих местах.

## 1. F-Droid (основной репозиторий)

F-Droid сам собирает приложение из исходников по тегу и подписывает своим ключом. Значит,
версию из F-Droid нельзя обновить APK с GitHub и наоборот (разные подписи) — это нормально.

1. Зарегистрироваться на gitlab.com и сделать fork https://gitlab.com/fdroid/fdroiddata.
2. В форке создать ветку `de.itmanpapa.callblocker` и добавить файл
   `metadata/de.itmanpapa.callblocker.yml` — содержимое
   [`de.itmanpapa.callblocker.yml`](de.itmanpapa.callblocker.yml) без комментариев в начале.
3. Проверка (по желанию локально, иначе это сделает CI в merge request). Проще всего в
   официальном контейнере `registry.gitlab.com/fdroid/fdroidserver:buildserver` (команды запуска —
   в Submitting Quick Start Guide, ссылка внизу), внутри него:
   ```
   cd /build                            # смонтированный fdroiddata
   fdroid readmeta
   fdroid rewritemeta de.itmanpapa.callblocker   # приводит файл к каноническому виду
   fdroid lint de.itmanpapa.callblocker
   fdroid checkupdates --allow-dirty de.itmanpapa.callblocker
   fdroid build -v -l de.itmanpapa.callblocker   # долго
   ```
4. Закоммитить (`New App: de.itmanpapa.callblocker`), запушить и открыть merge request в
   `fdroid/fdroiddata` по шаблону «App inclusion». В шаблоне отметить пункты и коротко написать:
   форк YACB (оригинал есть в F-Droid как `dummydomain.yetanothercallblocker`, не обновлялся с 2021),
   что нового (Material 3, PhoneBlock, Bundesnetzagentur, правила, карточка звонящего, статистика,
   резервная копия), что вариант `fdroid` не содержит самообновления, Anti-Feature NonFreeNet.
5. Дождаться CI (пайплайн собирает приложение) и ответить рецензентам в MR. Типичные вопросы:
   иконка форка, Anti-Features (могут добавить `Tracking`, если сочтут, что фоновые загрузки списков
   по умолчанию — это запросы к сторонним сервисам без согласия), сборка/сканер.
6. После merge приложение появляется в F-Droid с ближайшим циклом сборки. Дальнейшие версии
   F-Droid находит сам по новым тегам `vX.Y.Z` (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`):
   достаточно поднимать `versionCode`/`versionName` и ставить тег. Тег должен указывать на коммит,
   где версия уже поднята, и не переставляться после выпуска.

Сроки (по опыту, не гарантия): ревью MR — от нескольких дней до нескольких недель, бывает дольше;
после merge — по документации F-Droid около 24–48 часов до появления в репозитории
(страница на сайте — чуть позже); каждое следующее обновление — обычно несколько дней
после тега.

## 2. IzzyOnDroid

IzzyOnDroid не собирает из исходников, а берёт наш подписанный APK из GitHub Releases.

Требования (App Inclusion Policy): свободная лицензия и открытый код; без проприетарных библиотек,
рекламы и трекеров; описание в формате fastlane в репозитории (короткое и полное описание, иконка,
скриншоты); APK подписан релизным ключом, не debuggable; до 30 МБ на приложение (наш APK ~16 МБ);
самообновление допустимо только строго по согласию пользователя (opt-in). Поэтому для IzzyOnDroid
просим брать APK варианта `fdroid`.

1. Убедиться, что в релизе v0.13.0 есть `callguard_fdroid-v0.13.0.apk`.
2. Открыть issue «[AppRequest] CallGuard» в https://codeberg.org/IzzyOnDroid/repodata/issues
   (трекер переехал с GitLab на Codeberg; нужен аккаунт codeberg.org), шаблон App Request.
   Указать: репозиторий https://github.com/itmanpapa/Call, applicationId `de.itmanpapa.callblocker`,
   лицензия AGPL-3.0-only, что брать **только** `callguard_fdroid-*.apk` (регулярное выражение
   `callguard_fdroid-v.*\.apk`), метаданные в `fastlane/metadata/android`, Anti-Feature NonFreeNet.
3. Отвечать в issue на вопросы (обычно отчёт их сканера библиотек и разрешений).
4. После включения новые релизы подхватываются автоматически (скрипт регулярно проверяет GitHub
   Releases); ничего делать не нужно, кроме выпуска релиза с обоими APK.

Сроки: обычно от нескольких дней до пары недель на рассмотрение.

Подпись: IzzyOnDroid раздаёт наш APK с нашей подписью, F-Droid — свою сборку со своей подписью.
Пользователь не сможет переходить между ними без переустановки (данные — через резервную копию).

## 3. Что увидят пользователи

* F-Droid / IzzyOnDroid: нет пункта «Проверять обновления», кнопки «Проверить обновления» и
  уведомлений о новых версиях — обновляет магазин.
* GitHub: всё как раньше.

## 4. Воспроизводимая сборка с нашей подписью (по желанию, позже)

Тогда F-Droid публикует наш APK (та же подпись, что у IzzyOnDroid), если собранный им APK
совпадает с нашим байт в байт. Для этого в yml раскомментировать `Binaries:` (ссылка на
`callguard_fdroid-v%v.apk`) и `AllowedAPKSigningKeys:` (SHA-256 сертификата, строчными, без двоеточий):

```
keytool -printcert -jarfile callguard_fdroid-v0.13.0.apk | sed -n 's/[[:space:]]*SHA256: //p' | tr -d ':' | tr '[:upper:]' '[:lower:]'
```

Не проверено: совпадёт ли наша сборка (GitHub Actions, temurin 17) со сборкой F-Droid (Debian,
OpenJDK 17) — возможны отличия, например `baseline.prof`. Если сборки не совпадут, F-Droid эту версию
не опубликует, поэтому включать только после проверки (`fdroid build` + `apksigcopier compare`).

## Источники

* Inclusion Policy: https://f-droid.org/docs/Inclusion_Policy/
* Build Metadata Reference: https://f-droid.org/docs/Build_Metadata_Reference/
* Anti-Features: https://f-droid.org/docs/Anti-Features/
* Reproducible Builds: https://f-droid.org/docs/Reproducible_Builds/
* Submitting Quick Start Guide: https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/
* Метаданные оригинала: https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/dummydomain.yetanothercallblocker.yml
* IzzyOnDroid: https://izzyondroid.org/docs/general/AppInclusionPolicy/ ,
  https://codeberg.org/IzzyOnDroid/repodata
