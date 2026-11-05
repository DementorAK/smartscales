# Body composition: equations and reference ranges

This document describes how SmartScales calculates body composition with its built-in formula set. The exact
definitions the app uses, including every coefficient, are in
[`builtin.json`](../core/formula/src/main/resources/dev/kinash/smartscales/core/formula/builtin.json); this page explains
them and names the sources. The app calculates body composition from version 0.4.

> SmartScales is not a medical device. Its values are estimates for following your own trend over time; they are not
> a diagnosis. Talk to a health professional about your health.

## Principles

- **Only raw data is stored**: weight, impedance, heart rate, circumferences, and the person's height, sex and birth
  date at the time of the measurement. Every indicator is calculated from that data, so when an equation changes, the
  whole history is recalculated and the charts stay consistent.
- **Published equations only.** Every built-in equation comes from a peer-reviewed study or an official report, cited
  below. Indicators without a validated open equation are not included (see [Not included](#not-included)).
- **No value rather than a wrong value.** When an indicator lacks data (no impedance, a circumference not entered), it
  is not shown for that measurement; it never shows zero.
- **Adults only for body composition.** Body composition, basal metabolic rate and normal weight are calculated from
  age 18. Children get BMI, weight and height with growth chart percentiles (see [Children](#children)).
- **No upper age limit.** Equations are used beyond the age range of their study samples (for example, Wu 2015 studied
  ages 16–75). Switching equations at an age boundary would put a jump in a person's history, and the error of going
  beyond the sample is small compared with the uncertainty of the methods themselves.

## Floor scales measure foot to foot

Bathroom scales pass a small current from foot to foot and report a single impedance value at about 50 kHz. Most
published impedance equations were developed with laboratory analysers measuring hand to foot, often with resistance
and reactance separately. Applied to a floor scale, such equations give values shifted from laboratory methods. The
trend for one person on one scale is still meaningful.

For this reason the default fat-free mass equation is one developed for foot-to-foot scales (Wu 2015). Each paired
scale also has an **impedance calibration factor `k`** (default 1): the impedance is multiplied by `k` before any
equation. It can be fitted from one measurement on a laboratory analyser, such as DXA.

Some scales always report the same impedance whoever stands on them, a placeholder rather than a measurement. A body
does not give exactly the same impedance time after time, so when a scale has sent exactly the same value in three or
more weighings, SmartScales treats that value as missing and uses the equations without impedance for those
weighings. Other values from the same scale keep their impedance.

## Notation

W — weight, kg; H — height, cm; Hm — height, m; A — age, years; S — sex, 1 for men and 0 for women; Z — foot-to-foot
impedance, Ω (after the calibration factor); WC, HC, NC — waist, hip and neck circumference, cm.

## Variants and defaults

Several indicators have more than one equation. Variants of one indicator form a **group**; the first variant is used
by default, and each profile can choose another one. If the chosen variant has no value for a measurement (for
example, there is no impedance), the next variant of the group that has a value is used, and the app shows which one.

| Indicator | With impedance (default) | Without impedance |
|---|---|---|
| Body fat, % | from fat-free mass by Wu 2015 | Gallagher 2000 |
| Fat-free mass and fat mass | from body fat, adding up to the weight | from body fat, adding up to the weight |
| Body water | Sun 2003 | Watson 1980 |
| Skeletal muscle | Janssen 2000 | — |
| Basal metabolic rate | Katch–McArdle | Mifflin–St Jeor |

**Fat mass and fat-free mass always add up to the weight.** Fat mass is `W · body fat / 100` and fat-free mass is
`W − fat mass`, whichever body fat variant is in use. The fat-free mass equations below (Wu, Sun, Franco-Villoria) feed
the body fat estimate when impedance is available.

## Equations

### Body mass index and normal weight

| ID | Equation | Source |
|---|---|---|
| `bmi` | `W / Hm²` | WHO 2000 [1] |
| `ideal_weight_min`, `ideal_weight_max` | `18.5 · Hm²` … `24.9 · Hm²` | BMI limits of WHO 2000 [1] |

### Fat-free mass by impedance (group `ffm`)

These equations feed body fat when impedance is available. They are offered in the profile's choice of equations but
not shown among the indicators: the screens show the one fat-free mass that adds up with fat mass to the weight.

| ID | Equation | Source |
|---|---|---|
| `ffm_wu` (default) | `13.055 + 0.204·W + 0.394·H²/Z − 0.136·A + 8.125·S` | Wu 2015 [2]: foot-to-foot scale, DXA reference, 554 adults aged 16–75 |
| `ffm_sun` | men: `−10.68 + 0.65·H²/Z + 0.26·W + 0.02·Z`; women: `−9.53 + 0.69·H²/Z + 0.17·W + 0.02·Z` | Sun 2003 [3]: hand-to-foot, multicomponent reference, ages 12–94 |
| `ffm_bell` (women) | `TBW_bell / 0.732`, see body water | Franco-Villoria 2016 [4]: foot-to-foot, adult women |

### Body fat (group `bf`)

| ID | Equation | Source |
|---|---|---|
| `bf_bia` (default) | `(W − FFM) / W · 100`, FFM from the group above | — |
| `bf_gallagher` | `64.5 − 848/BMI + 0.079·A − 16.4·S + 0.05·S·A + 39.0·S/BMI` | Gallagher 2000 [5], equation 5: four-compartment reference, white and African American adults |
| `bf_deurenberg` | `1.2·BMI + 0.23·A − 10.8·S − 5.4` | Deurenberg 1991 [6] |
| `bf_navy` | body density, men: `1.0324 − 0.19077·log10(WC − NC) + 0.15456·log10(H)`; women: `1.29579 − 0.35004·log10(WC + HC − NC) + 0.22100·log10(H)`; then `100 · (4.95 / density − 4.50)` | Hodgdon & Beckett 1984 [7], body fat by Siri |

US Navy method: the reports measure men's waist at the level of the navel and women's at the narrowest point, and
women also need the hip circumference. Measuring a man's waist at the narrowest point gives too little fat.

### Lean body mass from weight and height (group `lbm`)

Lean body mass is a separate estimate; it is not used as fat-free mass.

| ID | Equation | Source |
|---|---|---|
| `lbm_boer` | men: `0.407·W + 0.267·H − 19.2`; women: `0.252·W + 0.473·H − 48.3` | Boer 1984 [8] |
| `lbm_hume` | men: `0.32810·W + 0.33929·H − 29.5336`; women: `0.29569·W + 0.41813·H − 43.2933` | Hume 1966 [9] |

### Body water (group `tbw`)

| ID | Equation | Source |
|---|---|---|
| `tbw_sun` (default) | men: `1.20 + 0.45·H²/Z + 0.18·W`; women: `3.75 + 0.45·H²/Z + 0.11·W`, litres | Sun 2003 [3] |
| `tbw_bell` (women) | `0.66·H²/Z`, litres | Franco-Villoria 2016 [4], after Bell 1998 [10] |
| `tbw_watson` | men: `2.447 − 0.09516·A + 0.1074·H + 0.3362·W`; women: `−2.097 + 0.1069·H + 0.2466·W`, litres | Watson 1980 [22]: without impedance, dilution studies of adults |
| `tbw_percent` | `TBW · 0.995 / W · 100` | — |

### Muscle and indices

| ID | Equation | Source |
|---|---|---|
| `smm_janssen` | `0.401·H²/Z + 3.825·S − 0.071·A + 5.102`, kg | Janssen 2000 [11]: hand-to-foot, MRI reference |
| `smi` | `SMM / Hm²` | Janssen 2004 [12] |
| `ffmi`, `fmi` | `fat-free mass / Hm²`, `fat mass / Hm²` (they add up to BMI) | VanItallie 1990 [13] |

### Basal metabolic rate (group `bmr`)

| ID | Equation | Source |
|---|---|---|
| `bmr_katch` (default with impedance) | `370 + 21.6 · fat-free mass` | Katch–McArdle, as given in McArdle, Katch & Katch [14] |
| `bmr_mifflin` | `10·W + 6.25·H − 5·A + 5` (men), `− 161` (women) | Mifflin 1990 [15] |
| `bmr_cunningham` | `500 + 22 · fat-free mass` | Cunningham 1980 [16] |

### Circumferences

| ID | Equation | Source |
|---|---|---|
| `waist_circumference` | `WC` | WHO 2008 [17] |
| `whtr` | `WC / H` | Browning 2010 [18], Ashwell 2011 [19] |
| `whr` | `WC / HC` | WHO 2008 [17] |

## Reference ranges

Ranges colour the values: low, normal, elevated, high. A value exactly at a bound belongs to the range above it,
unless the source states a strict "greater than". The value is compared as shown, rounded to its displayed precision.

**BMI, adults** (WHO 2000 [1]): below 18.5 underweight; 18.5–24.9 normal; 25–29.9 overweight; 30–34.9 obesity class I;
35–39.9 class II; 40 and above class III.

**Body fat, %**, all variants (Gallagher 2000 [5], Table 4: body fat matching BMI 18.5, 25 and 30 by sex and age). Ages
18–19 use the 20–39 ranges, ages 80 and above use the 60–79 ranges.

| | Age | Low | Normal | Elevated | High |
|---|---|---|---|---|---|
| Men | 20–39 | < 8 | 8–20 | 20–25 | ≥ 25 |
| Men | 40–59 | < 11 | 11–22 | 22–28 | ≥ 28 |
| Men | 60–79 | < 13 | 13–25 | 25–30 | ≥ 30 |
| Women | 20–39 | < 21 | 21–33 | 33–39 | ≥ 39 |
| Women | 40–59 | < 23 | 23–34 | 34–40 | ≥ 40 |
| Women | 60–79 | < 24 | 24–36 | 36–42 | ≥ 42 |

**Waist circumference, adults** (WHO 2008 [17], Table A1, for people of European origin): increased risk above 94 cm
for men and above 80 cm for women; substantially increased risk above 102 cm and above 88 cm. The WHO report presents
these cut-offs as an example from Han et al. 1995 rather than as its own recommendation.

**Waist-to-height ratio**: below 0.5 normal; 0.5 to 0.6 increased risk; 0.6 and above high. The 0.5 boundary comes from
a systematic review [18]; 0.6 was proposed for simplicity [19].

**Waist-to-hip ratio, adults** (WHO 2008 [17]): 0.90 and above for men, 0.85 and above for women — substantially
increased risk.

Skeletal muscle index, fat-free mass index, fat mass index, body water and basal metabolic rate are shown without
ranges.

## Children

Under 18, SmartScales shows BMI, weight and height against the **CDC 2000 growth charts** [20] instead of adult ranges
and body composition. The app includes the CDC data files for ages 2 to 20 (BMI-for-age, weight-for-age and
stature-for-age), a work of the U.S. government in the public domain, unchanged.

- The charts give, for each sex and month of age, the values L, M and S of the LMS method [21]. A measurement x has
  the z-score `((x / M)^L − 1) / (L · S)` (or `ln(x / M) / S` when L is 0), and its percentile is the standard normal
  distribution of z. L, M and S between tabulated ages are interpolated linearly; age is counted in months with a
  fraction from the birth date.
- **BMI-for-age weight status** (CDC): below the 5th percentile underweight; 5th to below 85th healthy weight; 85th to
  below 95th overweight; 95th and above obesity.
- BMI is placed on the chart as shown, to one decimal, like the adult ranges; weight and height as measured.
- **Above the 95th percentile** the CDC 2000 charts are extrapolated and understate how far a very high BMI is from
  the norm, which is why CDC published extended BMI-for-age charts in 2022 that express it as a percentage of the 95th
  percentile. SmartScales uses the 2000 charts: every BMI from the 95th percentile up is shown as obesity, with the
  percentile capped as "above 99" rather than as a precise number.
- **Under 2 years**: weight and height only, without BMI or percentiles.
- From 18 years the adult indicators and ranges apply.

## Not included

- **Visceral fat level, bone mass, protein, body age and body score.** Manufacturers' scales use their own proprietary
  scales for these, and there is no validated open equation for them from a single foot-to-foot impedance.
- **Equations that need reactance or hand-to-foot measurement**, including segmental fat and muscle: floor scales do
  not provide these inputs.
- **Equations for specific ethnic groups** (for example, the equations for Asian adults in Gallagher 2000): profiles do
  not record ethnicity.

## Your own formula sets

Formula sets are JSON files in the same format as the built-in set. A set can add indicators, change the coefficients
or ranges of existing ones, or hide them from the screens, without touching the built-in set: sets are layered on top
of it. A set with errors is not activated, and the app points to the place of each error.

Sets are imported in Settings → Formula sets, where they are ordered (a set higher in the list wins), switched on
and off, exported and deleted. The built-in set can be exported there too, as a starting point for a set of your own.
Importing a file with the id of a set already there replaces that set.

## References

1. WHO. Obesity: preventing and managing the global epidemic. Report of a WHO consultation. WHO Technical Report Series
   894. Geneva: WHO; 2000. https://iris.who.int/handle/10665/42330
2. Wu C.S., Chen Y.Y., Chuang C.L. et al. Predicting body composition using foot-to-foot bioelectrical impedance
   analysis in healthy Asian individuals. Nutrition Journal 2015;14:52. https://doi.org/10.1186/s12937-015-0041-0
3. Sun S.S., Chumlea W.C., Heymsfield S.B. et al. Development of bioelectrical impedance analysis prediction equations
   for body composition with the use of a multicomponent model for use in epidemiologic surveys. Am J Clin Nutr
   2003;77:331–340. https://doi.org/10.1093/ajcn/77.2.331
4. Franco-Villoria M., Wright C.M., McColl J.H. et al. Assessment of adult body composition using bioelectrical
   impedance: comparison of researcher calculated to machine outputted values. BMJ Open 2016;6:e008922.
   https://doi.org/10.1136/bmjopen-2015-008922
5. Gallagher D., Heymsfield S.B., Heo M. et al. Healthy percentage body fat ranges: an approach for developing
   guidelines based on body mass index. Am J Clin Nutr 2000;72:694–701. https://doi.org/10.1093/ajcn/72.3.694
6. Deurenberg P., Weststrate J.A., Seidell J.C. Body mass index as a measure of body fatness: age- and sex-specific
   prediction formulas. Br J Nutr 1991;65:105–114. https://doi.org/10.1079/BJN19910073
7. Hodgdon J.A., Beckett M.B. Prediction of percent body fat for U.S. Navy men from body circumferences and height.
   Report 84-11; and Prediction of percent body fat for U.S. Navy women from body circumferences and height. Report
   84-29. San Diego: Naval Health Research Center; 1984. https://apps.dtic.mil/sti/pdfs/ADA143890.pdf,
   https://apps.dtic.mil/sti/pdfs/ADA146456.pdf
8. Boer P. Estimated lean body mass as an index for normalization of body fluid volumes in humans. Am J Physiol
   1984;247:F632–F636. https://doi.org/10.1152/ajprenal.1984.247.4.F632
9. Hume R. Prediction of lean body mass from height and weight. J Clin Pathol 1966;19:389–391.
   https://doi.org/10.1136/jcp.19.4.389
10. Bell N.A., McClure P.D., Hill R.J., Davies P.S.W. Assessment of foot-to-foot bioelectrical impedance analysis for
    the prediction of total body water. Eur J Clin Nutr 1998;52:856–859. https://doi.org/10.1038/sj.ejcn.1600661
11. Janssen I., Heymsfield S.B., Baumgartner R.N., Ross R. Estimation of skeletal muscle mass by bioelectrical impedance
    analysis. J Appl Physiol 2000;89:465–471. https://doi.org/10.1152/jappl.2000.89.2.465
12. Janssen I., Baumgartner R.N., Ross R., Rosenberg I.H., Roubenoff R. Skeletal muscle cutpoints associated with
    elevated physical disability risk in older men and women. Am J Epidemiol 2004;159:413–421.
    https://doi.org/10.1093/aje/kwh058
13. VanItallie T.B., Yang M.U., Heymsfield S.B., Funk R.C., Boileau R.A. Height-normalized indices of the body's
    fat-free mass and fat mass: potentially useful indicators of nutritional status. Am J Clin Nutr 1990;52:953–959.
    https://doi.org/10.1093/ajcn/52.6.953
14. McArdle W.D., Katch F.I., Katch V.L. Exercise Physiology: Energy, Nutrition, and Human Performance. 5th ed. 2001.
15. Mifflin M.D., St Jeor S.T., Hill L.A. et al. A new predictive equation for resting energy expenditure in healthy
    individuals. Am J Clin Nutr 1990;51:241–247. https://doi.org/10.1093/ajcn/51.2.241
16. Cunningham J.J. A reanalysis of the factors influencing basal metabolic rate in normal adults. Am J Clin Nutr
    1980;33:2372–2374. https://doi.org/10.1093/ajcn/33.11.2372
17. WHO. Waist circumference and waist–hip ratio: report of a WHO expert consultation, Geneva, 8–11 December 2008.
    Geneva: WHO; 2011. https://iris.who.int/handle/10665/44583
18. Browning L.M., Hsieh S.D., Ashwell M. A systematic review of waist-to-height ratio as a screening tool for the
    prediction of cardiovascular disease and diabetes: 0·5 could be a suitable global boundary value. Nutr Res Rev
    2010;23:247–269. https://doi.org/10.1017/S0954422410000144
19. Ashwell M. Charts based on body mass index and waist-to-height ratio to assess the health risks of obesity: a
    review. The Open Obesity Journal 2011;3:78–84. https://doi.org/10.2174/1876823701103010078
20. Kuczmarski R.J., Ogden C.L., Guo S.S. et al. 2000 CDC Growth Charts for the United States: methods and
    development. Vital Health Stat 11. 2002;(246):1–190. Data files: https://www.cdc.gov/growthcharts/cdc-data-files.htm
21. Cole T.J. The LMS method for constructing normalized growth standards. Eur J Clin Nutr 1990;44:45–60.
22. Watson P.E., Watson I.D., Batt R.D. Total body water volumes for adult males and females estimated from simple
    anthropometric measurements. Am J Clin Nutr 1980;33:27–39. https://doi.org/10.1093/ajcn/33.1.27
