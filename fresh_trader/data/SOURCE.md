# Data source

- **Instrument:** EUR/USD spot
- **Source:** Dukascopy Bank SA historical data feed (free, public), downloaded with the
  open-source npm package `dukascopy-node` (M1 bid and M1 ask candles), on 2026-11-16.
- **Range:** 2015-01-01 22:00 UTC (first bar of 2015) to 2026-11-13 21:59 UTC (Friday close).
- **Timezone:** UTC everywhere. `time` = bar OPEN time. A bar closes at `time + bar length`.
- **Prices:** BID. Ask = bid + `spread`.
- **Rebuild script:** `build_data.py` (provenance only; you never need to run it).

## Columns (all files)
| Column | Meaning |
|---|---|
| `time` | Bar open time, UTC (timezone-aware) |
| `open`, `high`, `low`, `close` | Bid prices |
| `spread` | Ask close minus bid close, in price units (0.0001 = 1 pip). In M15/H1/H4/D1 it is the mean of the M1 spreads |
| `volume` | Dukascopy tick volume (millions of units); M15+ is the sum of M1 |

## Files
| File | Rows | First bar (UTC) | Last bar (UTC) | Size |
|---|---|---|---|---|
| `m1/EURUSD_M1_2015.parquet` | 372,944 | 2015-01-01 22:00 | 2015-12-31 21:59 | 5.1 MB |
| `m1/EURUSD_M1_2016.parquet` | 373,994 | 2016-01-03 22:00 | 2016-12-30 21:59 | 5.2 MB |
| `m1/EURUSD_M1_2017.parquet` | 373,106 | 2017-01-01 22:00 | 2017-12-29 21:59 | 5.0 MB |
| `m1/EURUSD_M1_2018.parquet` | 373,444 | 2018-01-01 22:00 | 2018-12-31 21:59 | 5.0 MB |
| `m1/EURUSD_M1_2019.parquet` | 373,253 | 2019-01-01 22:02 | 2019-12-31 21:59 | 4.8 MB |
| `m1/EURUSD_M1_2020.parquet` | 373,474 | 2020-01-01 22:02 | 2020-12-31 21:59 | 5.1 MB |
| `m1/EURUSD_M1_2021.parquet` | 370,685 | 2021-01-03 22:00 | 2021-12-31 21:59 | 4.8 MB |
| `m1/EURUSD_M1_2022.parquet` | 372,879 | 2022-01-02 22:03 | 2022-12-30 21:59 | 5.2 MB |
| `m1/EURUSD_M1_2023.parquet` | 371,125 | 2023-01-01 22:04 | 2023-12-29 21:59 | 5.0 MB |
| `m1/EURUSD_M1_2024.parquet` | 372,701 | 2024-01-01 22:00 | 2024-12-31 21:59 | 4.8 MB |
| `m1/EURUSD_M1_2025.parquet` | 372,068 | 2025-01-01 22:00 | 2025-12-31 21:58 | 4.9 MB |
| `m1/EURUSD_M1_2026.parquet` | 324,113 | 2026-01-01 22:04 | 2026-11-13 21:59 | 4.2 MB |
| `EURUSD_M15.parquet` | 295,973 | 2015-01-01 22:00 | 2026-11-13 21:45 | 5.6 MB |
| `EURUSD_H1.parquet` | 73,999 | 2015-01-01 22:00 | 2026-11-13 21:00 | 1.7 MB |
| `EURUSD_H4.parquet` | 19,126 | 2015-01-01 20:00 | 2026-11-13 20:00 | 0.6 MB |
| `EURUSD_D1.parquet` | 3,712 | 2015-01-01 00:00 | 2026-11-13 00:00 | 0.2 MB |

M15, H1, H4 and D1 are resampled from M1 on UTC boundaries (H4 bins start at 00, 04, 08,
12, 16, 20 UTC; D1 = UTC calendar day). Because the market opens Sunday ~21:00/22:00 UTC,
there is a short Sunday D1 bar each week. Empty periods produce no bar (no flat filler bars).

## Sanity checks (run 2026-11-16)
- Duplicated timestamps: 0. Bars with high < low or open/close outside high-low: 0.
- Missing spread: 0. Spread <= 0: 0. Median spread 0.3 pip, 99th pct 2.7 pip,
  1,196 M1 bars above 10 pips (mostly around the 21:00-22:00 UTC daily rollover and
  holiday opens).
- Gaps > 30 minutes that are not normal weekends (18):
  - Christmas and New Year closures every year (Dec 25 ~08:00-22:00 UTC, Dec 31 22:00 to Jan 1/3 22:00).
  - 2015-05-06 12:31-13:35 UTC (1h04m missing).
  - 2019-12-24 21:59-22:59 UTC (1h).
  - 2024-10-09 23:04 to 2024-10-10 00:11 UTC (1h07m missing).
  - 2025-12-24 23:28-23:59 UTC (31m).
- 57 M1 bars move more than 0.5% close-to-close. These line up with weekly opens
  (Sunday 21:00/22:00 UTC) and scheduled news minutes, plus the 2015-01-22 and 2016-12-29
  thin-market spikes. They are kept as-is (real prints), not cleaned.
