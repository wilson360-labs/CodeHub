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
