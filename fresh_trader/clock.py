"""Time-gated data access for the simulation.

All price data MUST be read through SimClock. It only ever returns bars that
have fully closed at or before the current simulation time.

Bar timestamps ("time") are the bar OPEN time in UTC. A bar is visible only
once time + bar_length <= sim_now.
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

DATA_DIR = Path(__file__).resolve().parent / "data"
TF_MINUTES = {"M1": 1, "M15": 15, "H1": 60, "H4": 240, "D1": 1440}


def _utc(ts) -> pd.Timestamp:
    t = pd.Timestamp(ts)
    return t.tz_localize("UTC") if t.tzinfo is None else t.tz_convert("UTC")


class SimClock:
    """Simulation clock. Starts at `start` (UTC). Can only move forward via
    advance()/set() and only exposes closed bars up to now."""

    def __init__(self, start="2015-01-05 00:00", data_dir: Path | str = DATA_DIR,
                 allow_rewind: bool = False):
        self.__now = _utc(start)
        self.__dir = Path(data_dir)
        self.__cache: dict[str, pd.DataFrame] = {}
        self.__allow_rewind = allow_rewind

    # ---- time control -------------------------------------------------
    @property
    def now(self) -> pd.Timestamp:
        return self.__now

    def advance(self, minutes: int = 0, hours: int = 0, days: int = 0) -> pd.Timestamp:
        step = pd.Timedelta(minutes=minutes, hours=hours, days=days)
        if step < pd.Timedelta(0):
            raise ValueError("advance() only moves forward")
        self.__now = self.__now + step
        return self.__now

    def set(self, ts) -> pd.Timestamp:
        """Jump to ts. Moving backwards is refused unless allow_rewind=True
        (useful for re-running a backtest from an earlier date)."""
        t = _utc(ts)
        if t < self.__now and not self.__allow_rewind:
            raise ValueError("set() cannot move backwards (create SimClock(allow_rewind=True))")
        self.__now = t
        return self.__now

    # ---- data ---------------------------------------------------------
    def _load(self, tf: str, upto: pd.Timestamp) -> pd.DataFrame:
        if tf not in TF_MINUTES:
            raise ValueError(f"tf must be one of {list(TF_MINUTES)}")
        if tf == "M1":
            frames = []
            for y in range(2015, upto.year + 1):
                key = f"M1_{y}"
                if key not in self.__cache:
                    p = self.__dir / "m1" / f"EURUSD_M1_{y}.parquet"
                    if not p.exists():
                        continue
                    self.__cache[key] = pd.read_parquet(p)
                frames.append(self.__cache[key])
            return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()
        if tf not in self.__cache:
            self.__cache[tf] = pd.read_parquet(self.__dir / f"EURUSD_{tf}.parquet")
        return self.__cache[tf]

    def get_bars(self, tf: str = "H1", lookback: int = 200) -> pd.DataFrame:
        """Return the last `lookback` CLOSED bars of timeframe tf as of now.
        Columns: time (bar open, UTC), open, high, low, close (bid),
        spread (avg ask-bid in price units, may be NaN), volume."""
        if lookback <= 0:
            return pd.DataFrame()
        now = self.__now
        df = self._load(tf, now)
        if df.empty:
            return df.copy()
        cutoff = now - pd.Timedelta(minutes=TF_MINUTES[tf])  # open time of last closed bar
        idx = df["time"].searchsorted(cutoff, side="right")
        out = df.iloc[max(0, idx - lookback):idx].copy().reset_index(drop=True)
        assert out.empty or out["time"].iloc[-1] + pd.Timedelta(minutes=TF_MINUTES[tf]) <= now
        return out

    def last_price(self) -> float | None:
        """Close (bid) of the last closed M1 bar."""
        b = self.get_bars("M1", 1)
        return None if b.empty else float(b["close"].iloc[-1])


def plot_chart(clock: SimClock, tf: str = "H1", lookback: int = 120, path: str = "chart.png",
               title: str | None = None) -> str:
    """Save a candlestick chart of closed bars (through the clock) to `path`."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    bars = clock.get_bars(tf, lookback)
    fig, ax = plt.subplots(figsize=(14, 6))
    if not bars.empty:
        w = 0.6
        for i, r in enumerate(bars.itertuples()):
            c = "#26a69a" if r.close >= r.open else "#ef5350"
            ax.vlines(i, r.low, r.high, color=c, linewidth=1)
            ax.add_patch(plt.Rectangle((i - w / 2, min(r.open, r.close)), w,
                                       max(abs(r.close - r.open), 1e-6), color=c))
        n = len(bars)
        ticks = list(range(0, n, max(1, n // 10)))
        ax.set_xticks(ticks)
        ax.set_xticklabels([bars["time"].iloc[i].strftime("%Y-%m-%d %H:%M") for i in ticks],
                           rotation=30, ha="right", fontsize=8)
        ax.set_xlim(-1, n)
    ax.set_title(title or f"EURUSD {tf} (UTC, bid) as of {clock.now:%Y-%m-%d %H:%M}")
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=100)
    plt.close(fig)
    return path
