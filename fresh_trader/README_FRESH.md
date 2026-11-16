# Fresh trader workspace (blind simulation)

You are a person who has never traded. Everything you know about trading must be
learned here, inside this simulation, from the price data and your own experiments.

## Blind rules (non-negotiable)
1. **Zero prior knowledge.** Do not bring in outside trading results, strategies or
   statistics as facts. You may form ideas, but each one must be tested here.
2. **Only use files in `fresh_trader/`.** Never clone, open or browse any other
   repository, branch, gist or folder on this machine or on GitHub. Do not search
   the web for backtest results on EUR/USD.
3. **All price data goes through `clock.py`** (`SimClock.get_bars`, `plot_chart`).
   Never read `data/` files directly and never look at bars after the current sim
   date. Moving the clock backwards is only allowed for re-testing an idea on a
   period you have already lived through.
4. **No new big data in git.** Do not download or commit extra price files.
   Charts, caches and scratch output stay out of git (see `.gitignore`).
5. **Costs are real.** Use `sim_account.Account` (spread, slippage, next-bar fills,
   stop-first rule) for every backtest and demo trade.
6. **Keep `STATE.md` current** so the work can resume at any time, and log every
   trade in `journal/`.

## Files
| Path | What it is |
|---|---|
| `clock.py` | `SimClock` time-gated data access + `plot_chart` candles |
| `sim_account.py` | Order fills, stops/targets, FundedNext 2-step challenge tracker |
| `test_clock.py`, `test_sim_account.py` | Tests (`pytest -q`) |
| `data/` | EUR/USD M1 (per year) + M15/H1/H4/D1, UTC, bid. See `data/SOURCE.md` |
| `STATE.md` | Current sim date, phase, strategy, stats, next step |
| `journal/` | Trade log and lessons |

## Quick start
```bash
pip install -r requirements.txt
pytest -q
python -c "from clock import SimClock, plot_chart; c=SimClock('2015-01-05'); c.advance(days=3); plot_chart(c,'H1',100,'charts/h1.png')"
```

## The target account
FundedNext 2-step, $25,000: Phase 1 +8%, Phase 2 +5%, max daily loss 5% of the
start-of-day balance (floating equity counts; day resets 00:00 server time =
17:00 New York), max loss 10%, minimum 5 trading days per phase.
