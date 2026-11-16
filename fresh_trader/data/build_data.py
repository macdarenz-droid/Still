"""Rebuild the parquet files from Dukascopy M1 CSVs (bid + ask) produced by:
  npx dukascopy-node -i eurusd -from YYYY-01-01 -to YYYY+1-01-01 -t m1 -f csv -p bid -v -fn bid_YYYY
  (same with -p ask). Usage: python build_data.py <csv_dir>
Provenance only; the simulation never needs to run this."""
import sys
from pathlib import Path

import numpy as np
import pandas as pd

OUT = Path(__file__).resolve().parent


def load(p):
    d = pd.read_csv(p)
    d["time"] = pd.to_datetime(d["timestamp"], unit="ms", utc=True)
    return d.drop(columns="timestamp")


def resample(m1, rule):
    g = m1.set_index("time").resample(rule, label="left", closed="left")
    r = pd.DataFrame({"open": g["open"].first(), "high": g["high"].max(), "low": g["low"].min(),
                      "close": g["close"].last(), "spread": g["spread"].mean(),
                      "volume": g["volume"].sum()}).dropna(subset=["open"])
    r["spread"] = r["spread"].astype("float32")
    r["volume"] = r["volume"].astype("float32")
    return r.reset_index()


def main(src):
    src = Path(src)
    (OUT / "m1").mkdir(exist_ok=True)
    allm1 = []
    for y in range(2015, 2027):
        b = load(src / f"bid_{y}.csv")
        a = load(src / f"ask_{y}.csv")[["time", "close"]].rename(columns={"close": "ask_close"})
        m = b.merge(a, on="time", how="left")
        m = m[m["time"].dt.year == y]
        m["spread"] = (m["ask_close"] - m["close"]).round(6).astype("float32")
        m["volume"] = m["volume"].astype("float32")
        m = m[["time", "open", "high", "low", "close", "spread", "volume"]].sort_values("time")
        m = m.drop_duplicates("time").reset_index(drop=True)
        m.to_parquet(OUT / "m1" / f"EURUSD_M1_{y}.parquet", compression="zstd",
                     compression_level=19, index=False)
        allm1.append(m)
    m1 = pd.concat(allm1, ignore_index=True)
    for tf, rule in [("M15", "15min"), ("H1", "1h"), ("H4", "4h"), ("D1", "1D")]:
        resample(m1, rule).to_parquet(OUT / f"EURUSD_{tf}.parquet", compression="zstd",
                                      compression_level=19, index=False)


if __name__ == "__main__":
    main(sys.argv[1])
