"""Proves the clock never leaks future data. Uses a synthetic dataset so it
runs anywhere; the last test also checks the real data if present."""
import numpy as np
import pandas as pd
import pytest

from clock import DATA_DIR, TF_MINUTES, SimClock, plot_chart


@pytest.fixture()
def synth(tmp_path):
    t = pd.date_range("2015-01-05 00:00", "2015-01-07 23:59", freq="1min", tz="UTC")
    px = 1.2 + np.cumsum(np.random.default_rng(0).normal(0, 1e-5, len(t)))
    m1 = pd.DataFrame({"time": t, "open": px, "high": px + 1e-5, "low": px - 1e-5,
                       "close": px, "spread": np.float32(1e-4), "volume": np.float32(1)})
    (tmp_path / "m1").mkdir()
    m1.to_parquet(tmp_path / "m1" / "EURUSD_M1_2015.parquet")
    for tf, rule in [("M15", "15min"), ("H1", "1h"), ("H4", "4h"), ("D1", "1D")]:
        g = m1.set_index("time").resample(rule)
        r = pd.DataFrame({"open": g.open.first(), "high": g.high.max(), "low": g.low.min(),
                          "close": g.close.last(), "spread": g.spread.mean(),
                          "volume": g.volume.sum()}).reset_index()
        r.to_parquet(tmp_path / f"EURUSD_{tf}.parquet")
    return tmp_path


@pytest.mark.parametrize("tf", list(TF_MINUTES))
def test_only_closed_bars(synth, tf):
    c = SimClock("2015-01-06 10:37", data_dir=synth)
    b = c.get_bars(tf, 10_000)
    assert not b.empty or tf == "D1"
    if not b.empty:
        assert (b["time"] + pd.Timedelta(minutes=TF_MINUTES[tf]) <= c.now).all()


def test_unfinished_candles_excluded(synth):
    c = SimClock("2015-01-06 10:30", data_dir=synth)
    assert c.get_bars("H1", 1)["time"].iloc[-1] == pd.Timestamp("2015-01-06 09:00", tz="UTC")
    assert c.get_bars("H4", 1)["time"].iloc[-1] == pd.Timestamp("2015-01-06 04:00", tz="UTC")
    assert c.get_bars("D1", 1)["time"].iloc[-1] == pd.Timestamp("2015-01-05", tz="UTC")
    assert c.get_bars("M15", 1)["time"].iloc[-1] == pd.Timestamp("2015-01-06 10:15", tz="UTC")
    assert c.get_bars("M1", 1)["time"].iloc[-1] == pd.Timestamp("2015-01-06 10:29", tz="UTC")
    c.set("2015-01-06 11:00")  # exactly at close -> the 10:00 H1 bar becomes visible
    assert c.get_bars("H1", 1)["time"].iloc[-1] == pd.Timestamp("2015-01-06 10:00", tz="UTC")


def test_before_data_returns_nothing(synth):
    c = SimClock("2015-01-01", data_dir=synth)
    for tf in TF_MINUTES:
        assert c.get_bars(tf, 100).empty


def test_cannot_go_back_or_peek(synth):
    c = SimClock("2015-01-06 10:00", data_dir=synth)
    with pytest.raises(ValueError):
        c.set("2015-01-05")
    with pytest.raises(ValueError):
        c.advance(minutes=-5)
    with pytest.raises(ValueError):
        c.get_bars("W1", 5)
    b = c.get_bars("M1", 10)
    b.loc[:, "close"] = 0  # returned frame is a copy
    assert c.get_bars("M1", 10)["close"].iloc[-1] != 0


def test_advance_reveals_gradually(synth):
    c = SimClock("2015-01-06 10:00", data_dir=synth)
    n = len(c.get_bars("M1", 100_000))
    c.advance(minutes=5)
    assert len(c.get_bars("M1", 100_000)) == n + 5


def test_plot(synth, tmp_path):
    c = SimClock("2015-01-06 12:00", data_dir=synth)
    assert (tmp_path / "x.png").exists() is False
    plot_chart(c, "M15", 40, str(tmp_path / "x.png"))
    assert (tmp_path / "x.png").stat().st_size > 0


@pytest.mark.skipif(not (DATA_DIR / "EURUSD_H1.parquet").exists(), reason="no real data")
def test_real_data_no_future():
    c = SimClock("2015-01-05 00:00")
    for tf in TF_MINUTES:
        b = c.get_bars(tf, 5000)
        assert b.empty or (b["time"] + pd.Timedelta(minutes=TF_MINUTES[tf]) <= c.now).all()
    c.set("2020-03-18 14:07")
    for tf in TF_MINUTES:
        b = c.get_bars(tf, 50)
        assert len(b) == 50
        assert (b["time"] + pd.Timedelta(minutes=TF_MINUTES[tf]) <= c.now).all()
