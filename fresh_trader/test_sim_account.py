import pandas as pd
from sim_account import Account, FundedNextChallenge, PIP, server_day


def bar(t, o, h, l, c):
    return dict(time=pd.Timestamp(t, tz="UTC"), open=o, high=h, low=l, close=c)


def test_fill_next_open_with_spread():
    a = Account(slippage=0)
    a.market(1, 1.0)
    assert not a.positions
    a.on_bar(bar("2020-01-02 10:00", 1.1000, 1.1005, 1.0995, 1.1001))
    assert abs(a.positions[0].entry - (1.1000 + PIP)) < 1e-9


def test_both_hit_assumes_stop():
    a = Account(slippage=0)
    a.market(1, 1.0, sl=1.0990, tp=1.1020)
    a.on_bar(bar("2020-01-02 10:00", 1.1000, 1.1001, 1.0999, 1.1000))
    a.on_bar(bar("2020-01-02 10:01", 1.1000, 1.1030, 1.0980, 1.1000))
    assert a.trades[-1]["reason"] == "sl"
    assert a.trades[-1]["pnl"] < 0


def test_gap_through_stop_fills_at_open():
    a = Account(slippage=0)
    a.market(-1, 1.0, sl=1.1050)
    a.on_bar(bar("2020-01-02 10:00", 1.1000, 1.1001, 1.0999, 1.1000))
    a.on_bar(bar("2020-01-03 10:00", 1.1100, 1.1110, 1.1090, 1.1100))
    assert abs(a.trades[-1]["exit"] - (1.1100 + PIP)) < 1e-9


def test_server_day_rolls_at_17_ny():
    assert server_day("2020-01-02 21:59").day == 2   # winter: 22:00 UTC = 00:00 server
    assert server_day("2020-01-02 22:00").day == 3
    assert server_day("2020-07-02 21:00").day == 3   # summer: 21:00 UTC


def test_challenge_daily_loss_and_pass():
    c = FundedNextChallenge()
    assert c.update("2020-01-02 10:00", 25_000, 23_700) == "failed"
    c = FundedNextChallenge()
    for i in range(5):
        c.update(f"2020-01-{6+i:02d} 10:00", 25_000 + 500 * (i + 1), 25_000 + 500 * (i + 1), traded=True)
    assert c.phase == 2
    for i in range(5):
        st = c.update(f"2020-01-{13+i} 10:00", 25_000 + 300 * (i + 1), 25_000 + 300 * (i + 1), traded=True)
    assert st == "passed"
