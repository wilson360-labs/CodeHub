# CodeHub APK — Android Native App

Native Android wrapper for CodeHub PWA. Pure Gradle project — no Bubblewrap needed.

## Structure

```
android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/codehub/app/
│   │   └── MainActivity.java     # WebView wrapper
│   └── res/
│       ├── values/strings.xml
│       ├── values/themes.xml
│       └── xml/network_security_config.xml
├── app/build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## How it works

- `MainActivity.java` loads `https://wilson360-labs.vercel.app` in a full-screen WebView
- Handles: file upload, geolocation, camera, notifications, back navigation
- Status bar + navigation bar match CodeHub theme (#080810)

## CI Build

GitHub Actions builds the APK automatically on push to `main`.  
Keystore is generated at build time from GitHub Secrets.

Required secrets:
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`

## Music player ("slide")

The APK bundles a native music player that reuses the **Echo** ecosystem
(`dev.brahmkshatriya.echo:common` 1.0.0 + third-party extension APKs).

Licensing:

- The player and feed code in `app/src/main/java/com/codehub/app/music/`
  is adapted from [Echo](https://github.com/brahmkshatriya/echo) (its
  `StreamableMediaSource`, `StreamableResolver`, `MediaItemUtils`,
  `ExtensionUtils`, feed loader and player). Echo is licensed under the
  **UPL-1.0** Open License and its app code under the **GPL-3.0**. This
  adaptation keeps that licensing: music feature code is provided under the
  same terms.
- Extensions loaded by the player are third-party APKs; their licenses
  remain with their authors.

## CodeStream

The Android APK also integrates the native player from
[Koustubh12345/YTXTube](https://github.com/Koustubh12345/YTXTube), release
2.2.0, as the **CodeStream** module at `android/codestream/`. Its source and
upstream license are included in that directory. The integration removes the
YTXTube self-updater and launcher entry point; CodeHub's own app updater stays
in the main app.

YTXTube is licensed under **GPL-3.0**. Because CodeStream is linked into the
combined Android APK, distribution of that combined Android program must
comply with GPL-3.0. This does not change the license of the separate CodeHub
web project. Existing MIT notices and notices for other bundled components
remain applicable to their respective code.
