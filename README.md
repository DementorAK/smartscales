# SmartScales

Free, open-source Android app for Chipsea / OKOK Bluetooth body composition scales.
No ads, no accounts, no paid features.

> Status: early development (0.3.0). Family profiles, manual entries and weighing with Chipsea broadcast scales
> (`C0`, including encrypted V2 with a key entered by hand or imported from a file, and `CA 20`) work. Scales that need a
> GATT connection only show a weight preview for now, and body composition is not calculated yet.

## Planned features

- All Chipsea / OKOK floor-scale protocols: broadcast (including encrypted V2), GATT, OKOKCloud, CloudV4 (8 electrodes, heart rate)
- Multiple family profiles with automatic person detection and scale-side user/history sync
- Body composition from published scientific equations; only raw measurements are stored, so the whole history is recalculated when formulas change
- Formula constructor: custom indicators, coefficients and reference ranges, shared as JSON files
- Customizable dashboard, charts, comparisons, goals with forecasts
- Children: BMI-for-age percentiles (CDC 2000)
- Local storage, CSV import/export, Health Connect, Google Drive backup
- Android 8.0+, Material 3, 12 languages

## How body composition is calculated

SmartScales stores only raw measurements (weight, impedance, circumferences, and height, sex and birth date at the time)
and calculates every indicator from them, so the whole history is recalculated when an equation changes. The built-in
equations all come from peer-reviewed studies and official reports:

| Indicator | With impedance | Without impedance |
|---|---|---|
| Body fat | from fat-free mass by Wu et al. 2015, an equation for foot-to-foot scales | Gallagher et al. 2000 |
| Body water | Sun et al. 2003 | Watson et al. 1980 |
| Skeletal muscle | Janssen et al. 2000 | — |
| Basal metabolic rate | Katch–McArdle | Mifflin–St Jeor 1990 |
| BMI, waist-to-height and waist-to-hip ratios | WHO, Ashwell | WHO, Ashwell |

Alternative equations (Sun 2003 and Franco-Villoria 2016 for fat-free mass, Deurenberg 1991 and the US Navy method for
body fat, Boer and Hume for lean body mass, Cunningham 1980 for metabolic rate) can be chosen per profile. Reference
ranges come from WHO (BMI, waist) and Gallagher et al. 2000 (body fat by sex and age). Body composition is calculated
for adults only.

Floor scales measure impedance from foot to foot, while most published equations were developed with hand-to-foot
laboratory analysers. Absolute values therefore differ from laboratory methods, but the trend for one person on one
scale is meaningful. Each scale has an optional impedance calibration factor.

Every equation, coefficient, reference range and source is listed in [docs/FORMULAS.md](docs/FORMULAS.md). You can add
your own formula sets as JSON files on top of the built-in one.

> SmartScales is not a medical device. Its values are estimates for following your own trend, not a diagnosis.

## Project structure

| Module | Kind | Contents |
|---|---|---|
| `:app` | Android app | UI (Jetpack Compose, Material 3), navigation, DI wiring |
| `:core:model` | Pure Kotlin | Shared types without dependencies: sex, units, raw measurement and person snapshot |
| `:core:protocol` | Pure Kotlin | Chipsea / OKOK frame parsing, command building, V2 decryption |
| `:core:ble` | Android library | Scale drivers and BLE advertisement / GATT adapters |
| `:core:formula` | Pure Kotlin | Formula expression engine and JSON formula sets |
| `:core:data` | Android library | Room database (raw measurements only), repositories, settings |
| `:lint` | Lint checks | Project lint rules, e.g. `ComposeHardcodedText`: UI text in Compose must come from string resources |

## Building

Requires JDK 25 and the Android SDK. `./gradlew build` assembles the app and runs unit tests and lint.

Release builds are signed only when these variables are set, either as environment variables or in `local.properties`:
`SMARTSCALES_KEYSTORE_FILE`, `SMARTSCALES_KEYSTORE_PASSWORD`, `SMARTSCALES_KEY_ALIAS`, `SMARTSCALES_KEY_PASSWORD`.

## Credits

Parts of the scale driver code are planned to be adapted from [openScale](https://github.com/oliexdev/openScale)
(GPL-3.0). Adapted files keep their original copyright notices. The body composition equations are written from the
original publications listed in [docs/FORMULAS.md](docs/FORMULAS.md).

Children's growth percentiles use the [CDC 2000 growth charts](https://www.cdc.gov/growthcharts/cdc-data-files.htm)
(public domain); the data files are included unchanged in `core/formula/src/main/resources/.../cdc/`.

Icons in `app/src/main/res/drawable/ic_*.xml` (except the launcher icon and `ic_scale.xml`) are
[Material Icons](https://github.com/google/material-design-icons) by Google (Apache License 2.0); `ic_manual_entry.xml` is
the "pan_tool" icon, scaled down, with a plus added.

The scale driver interface in `:core:ble` (`ScaleDriver`, `DeviceSupport` with capabilities versus what is
implemented, `ScaleDrivers`) follows the design of openScale's `ScaleDeviceHandler` and `ScaleFactory`;
the code is written anew.

## License

[GNU General Public License v3.0](LICENSE)
