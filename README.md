# KeshevAdom 🚨

[![Release](https://img.shields.io/github/v/release/Attius-Digital-Art/home-front-alert)](https://github.com/Attius-Digital-Art/home-front-alert/releases)
[![Internal Testing](https://img.shields.io/badge/Play%20Store-Internal%20Testing-blue)](https://play.google.com/console)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> **Real-time, distance-aware Home Front Command alerts for Android.**  
> הוגדר לפי קרבה לאזור האירוע — לא רק התרעה, אלא מידת הדחיפות.

---

## What Makes This Different

Unlike traditional alert apps that provide the same sound for every alert, this app provides **Eyes-Free Situational Awareness** through intelligent sound patterns:

- 🔊 **Sound Pattern Distinction** — Distinguish between near-zone emergencies and distant country-wide events through unique audio patterns without ever unlocking your phone.
- 🚗 **Car/Pocket Awareness** — Designed for use while driving or when the phone is in your pocket; the sound itself tells you the level of urgency and location context.
- 🌍 **Full Context, Low Intrusion** — Stay informed of all alerts across Israel with subtle distinctions, so you have full situational awareness without the false-urgency fatigue of generic alarms.
- 📡 **Hybrid Polling** — Bypasses FCM notification lag during high-volume events by using a direct polling fallback, ensuring you hear the pattern the moment the siren is triggered.
- 🌍 **Hebrew & English** — Full bilingual support in the UI.
- 💰 **Two Modes** — Free (direct polling, no backend) and Pro (Firebase push, lower battery, instant delivery).

---

## Architecture

```
android-app/          # Android Kotlin app (com.attius.homefrontalert)
│
├── MainActivity.kt           # Dashboard & status display
├── LocalPollingService.kt    # Direct HFC API polling (Free tier)
├── MyFirebaseMessagingService.kt  # FCM push handler (Pro tier)
├── DynamicToneGenerator.kt   # Distance-based audio engine
├── ZoneDistanceCalculator.kt # GPS → alert zone distance logic
├── SettingsActivity.kt       # GPS, volume, language, advanced
├── TOSActivity.kt            # Terms of Service (first launch)
└── AppLocationManager.kt     # Fused Location Provider wrapper

backend/              # Node.js Cloud Run backend (Pro tier)
└── index.js          # HFC relay + Firebase push trigger
```

---

## Build & Run

### Prerequisites
- Android Studio Meerkat or newer
- JDK 17
- `google-services.json` in `android-app/app/` (get from Firebase Console)

### Debug Build
```bash
cd android-app
./gradlew assembleFreeDebug
```

### Release Bundle (for Play Store)
```bash
cd android-app
./gradlew bundleFreeRelease
```
> Requires `release.jks` in `android-app/` — **never committed to git**.

---

## DevOps / CI-CD

Pushing a version tag automatically builds and deploys to **Internal Testing**:

```bash
git tag v1.3.4
git push origin v1.3.4
```

Required GitHub Secrets:
| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded `release.jks` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`homefront`) |
| `KEY_PASSWORD` | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | GCP service account with Play API access |

---

## Branch Strategy

| Branch | Purpose |
|---|---|
| `main` | Stable production code. Protected. PRs only. |
| `develop` | Integration branch for features |
| `feature/*` | Individual feature work |
| `hotfix/*` | Urgent production fixes |
| `release/x.y.z` | Release candidate — freeze, test, tag |

---

## Security Notes

- ❌ `release.jks` is **never** committed. Store securely (e.g., 1Password + GitHub Secret).
- ❌ `google-services.json` checked in contains **no private secrets** (safe Firebase config only).
- ✅ ProGuard/R8 minification active on release builds.
- ✅ Org-level IAM policy on GCP protects backend resources.
- ✅ Budget hard-cap (₪50/month) with auto-shutdown via Pub/Sub.

---

## Privacy Policy

[View Privacy Policy](https://storage.googleapis.com/home-front-policy-attius/privacy_policy.html)

---

## License

MIT License — see [LICENSE](LICENSE).

---

## Disclaimer

This app is an **independent, unofficial** companion to the Israel Home Front Command's alert system.  
It is **not affiliated** with the IDF, Pikud HaOref, or any government body.  
**Always follow official instructions during an emergency.**
