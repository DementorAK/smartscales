# SmartScales

Free, open-source Android app for Chipsea / OKOK Bluetooth body composition scales.
No ads, no accounts, no paid features.

> Status: early development — not usable yet.

## Planned features

- All Chipsea / OKOK floor-scale protocols: broadcast (including encrypted V2), GATT, OKOKCloud, CloudV4 (8 electrodes, heart rate)
- Multiple family profiles with automatic person detection and scale-side user/history sync
- Body composition from published scientific equations; only raw measurements are stored, so the whole history is recalculated when formulas change
- Formula constructor: custom indicators, coefficients and reference ranges, shared as JSON files
- Customizable dashboard, charts, comparisons, goals with forecasts
- Children: BMI-for-age percentiles (CDC 2000)
- Local storage, CSV import/export, Health Connect, Google Drive backup
- Android 8.0+, Material 3, 12 languages

## Project structure

| Module | Kind | Contents |
|---|---|---|
| `:app` | Android app | UI (Jetpack Compose, Material 3), navigation, DI wiring |
| `:core:protocol` | Pure Kotlin | Chipsea / OKOK frame parsing, command building, V2 decryption |
| `:core:ble` | Android library | Scale drivers and BLE advertisement / GATT adapters |
| `:core:formula` | Pure Kotlin | Formula expression engine and JSON formula sets |
| `:core:data` | Android library | Room database (raw measurements only), repositories, settings |

## Building

Requires JDK 25 and the Android SDK. `./gradlew build` assembles the app and runs unit tests and lint.

Release builds are signed only when these variables are set, either as environment variables or in `local.properties`:
`SMARTSCALES_KEYSTORE_FILE`, `SMARTSCALES_KEYSTORE_PASSWORD`, `SMARTSCALES_KEY_ALIAS`, `SMARTSCALES_KEY_PASSWORD`.

## Credits

Parts of the scale driver architecture and scientific formula implementations are planned to be adapted from
[openScale](https://github.com/oliexdev/openScale) (GPL-3.0). Adapted files keep their original copyright notices.

## License

[GNU General Public License v3.0](LICENSE)
