# Building

## Clone the project repo

```
git clone https://github.com/itmanpapa/Call.git YetAnotherCallBlocker
```

### Clone the assets repo (optional step: allows to avoid the initial DB downloading after installation)

```
git clone https://gitlab.com/xynngh/YetAnotherCallBlocker_data.git
```

Sym-link the assets:

Linux
```
cd YetAnotherCallBlocker/app/src/main/assets/
ln -s ../../../../../YetAnotherCallBlocker_data/assets/sia .
```
Windows
```
cd YetAnotherCallBlocker\app\src\main\assets
mklink /d sia ..\..\..\..\..\YetAnotherCallBlocker_data\assets\sia
```

**or** copy the whole directory `YetAnotherCallBlocker_data/assets/sia` into `YetAnotherCallBlocker/app/src/main/assets/`.


## Build the app

Open and build the project in Android Studio or use Gradle:
```
./gradlew build
```

## Build variants

The app has two product flavors with the same `applicationId`:

* `github` — the APK of our GitHub releases, with the in-app updater
  (`./gradlew assembleGithubRelease`, output in `app/build/outputs/apk/github/`);
* `fdroid` — for F-Droid and IzzyOnDroid, without any self-update code path: no update
  check, no APK download, no `REQUEST_INSTALL_PACKAGES`
  (`./gradlew assembleFdroidRelease`, output in `app/build/outputs/apk/fdroid/`).

The release workflow attaches both to a GitHub release: `callguard-vX.Y.Z.apk` (`github`)
and `callguard_fdroid-vX.Y.Z.apk` (`fdroid`). See `docs/fdroid/SUBMISSION.md`.
