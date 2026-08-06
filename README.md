# Sweep Acceptance Lab

A modular Python backtest engine, FastAPI/Vercel dashboard, and installable Android WebView app for testing **Sweep/Reclaim** versus **Acceptance Breakout** on OHLC candle data.

## Method definitions

The method uses a rolling high/low from completed candles only.

- `SWEEP_LOW_RECLAIM` → Long: price trades below the prior rolling low, then the candle closes back above it with a minimum lower-wick ratio.
- `SWEEP_HIGH_RECLAIM` → Short: price trades above the prior rolling high, then closes back below it with a minimum upper-wick ratio.
- `ACCEPTANCE_ABOVE` → Long: one or more consecutive candles close above the prior rolling high; the final candle must have a sufficiently large body and close near its high.
- `ACCEPTANCE_BELOW` → Short: mirrored bearish acceptance.

Signals are read at candle close. Entries occur at the **next candle open**. If SL and TP are both inside the same candle, the engine uses the conservative `SL first` assumption.

## App features

- Upload CSV from browser or Android file picker.
- Test Sweep only, Acceptance only, or both.
- Change lookback, ATR, penetration, wick/body thresholds, RR, maximum hold, and cost in R.
- View total trades, win rate, profit factor, expectancy, total R, drawdown, setup breakdown, direction breakdown, equity curve, and the latest trades.
- Maximum web/API request: 250,000 candles and approximately 4 MB CSV text. Larger historical data should be split by period.

CSV columns:

```text
timestamp,open,high,low,close,volume
```

`volume` is optional. `date` + `time`, `datetime`, and `timestamp` formats are accepted.

## Project structure

- `strategies/acceptance_sweep.py` — method definition.
- `engine/backtest.py` — next-open candle engine, SL/TP, time exit, cost, and conservative same-bar handling.
- `api/index.py` — FastAPI GET/POST API for uploaded or repository data.
- `public/index.html` — mobile-first test dashboard.
- `android-app/` — Android WebView wrapper with CSV file chooser.
- `analysis/report.py` — metrics, setup breakdown, trade preview, and equity chart.
- `tests/test_acceptance_sweep.py` — anti-regression tests for signal and execution truth.

## Run locally

```bash
pip install -r requirements.txt pytest
pytest -q
uvicorn api.index:app --reload --host 0.0.0.0 --port 8000
```

Open `http://localhost:8000` through the configured static server/Vercel deployment, or run the CLI:

```bash
python main.py
```

## Build Android APK

GitHub Actions workflow: **Build Sweep Acceptance Preview APK**.

Use `workflow_dispatch` and set:

- `versionName`
- `versionCode`
- `webUrl` for the deployed Vercel dashboard

The workflow runs Python regression tests, smoke-tests the API engine, builds an installable debug-signed APK, verifies package/version/signature, and uploads the APK artifact for 30 days.

The Android package is separate from Amy FX:

```text
com.amy.sweepacceptancelab.debug
```

## Important limits

This repository is a testing laboratory, not a claim that Sweep or Acceptance is profitable. A result is only meaningful when the same rule is tested with realistic costs, next-open execution, sufficient samples, and untouched out-of-sample data.
