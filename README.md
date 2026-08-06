# Sweep Acceptance Lab

A modular Python backtest engine, FastAPI/Vercel dashboard, and installable Android application for testing **Sweep/Reclaim** versus **Acceptance Breakout** on OHLC candle data.

## Method definitions

The method uses a rolling high/low from completed candles only.

- `SWEEP_LOW_RECLAIM` → Long: price trades below the prior rolling low, then the candle closes back above it with a minimum lower-wick ratio.
- `SWEEP_HIGH_RECLAIM` → Short: price trades above the prior rolling high, then closes back below it with a minimum upper-wick ratio.
- `ACCEPTANCE_ABOVE` → Long: one or more consecutive candles close above the prior rolling high; the final candle must have a sufficiently large body and close near its high.
- `ACCEPTANCE_BELOW` → Short: mirrored bearish acceptance.

Signals are read at candle close. Entries occur at the **next candle open**. If SL and TP are both inside the same candle, the engine uses the conservative `SL first` assumption.

## Android large-data mode

The APK contains its own dashboard and a native Java streaming backtest engine. It does not send the archive to Vercel.

- Select a file directly from local storage or the Google Drive provider in Android's file picker.
- Accepts one CSV/TXT, one ZIP containing hundreds of monthly CSV files, or a master ZIP containing monthly ZIP archives.
- Entries are processed in natural filename order.
- Indicator, pending-signal, open-trade, equity, and drawdown state continues across monthly file boundaries.
- Data is read line by line. The complete archive is never expanded into RAM.
- There is no hard 250,000-candle limit in the APK. A seven-million-candle archive is supported by the streaming design; practical limits are device cache/storage, battery, and processing time.
- Nested ZIP depth is limited to three levels as protection against malformed archives.
- The latest 100 trades and a compacted equity curve are retained for display; all candles still participate in the calculation.

The selected top-level ZIP is copied to temporary app cache so Android can access entries randomly and process them in chronological filename order. Temporary archive files are deleted when the run finishes.

## Web/API mode

The browser/Vercel version remains useful for small CSV tests. Its request limit is intentionally smaller:

- approximately 4 MB CSV text;
- maximum 250,000 candles per API request.

Those are transport/serverless limits, not limits of the strategy or native Android engine.

## App features

- Test Sweep only, Acceptance only, or both.
- Change lookback, ATR, penetration, wick/body thresholds, RR, maximum hold, and cost in R.
- View total trades, win rate, profit factor, expectancy, total R, drawdown, setup breakdown, direction breakdown, equity curve, and the latest trades.
- Cancel a long local run without closing the application.

CSV columns:

```text
timestamp,open,high,low,close,volume
```

`volume` is optional. `date` + `time`, `datetime`, and `timestamp` formats are accepted. Comma, semicolon, and tab-delimited files are detected automatically.

## Project structure

- `strategies/acceptance_sweep.py` — Python/API method definition.
- `engine/backtest.py` — Python next-open candle engine.
- `api/index.py` — FastAPI GET/POST API for small browser runs.
- `public/index.html` — dashboard packaged both for Vercel and inside the APK.
- `android-app/app/src/main/java/com/amy/tradebacktest/LocalZipBacktestBridge.java` — local ZIP reader and streaming multi-million-candle backtest engine.
- `android-app/app/src/main/java/com/amy/tradebacktest/MainActivity.java` — Android file picker, Google Drive URI access, and WebView/native bridge.
- `analysis/report.py` — Python metrics and charts.
- `tests/test_acceptance_sweep.py` — anti-regression tests for signal and execution truth.

## Run Python locally

```bash
pip install -r requirements.txt pytest
pytest -q
uvicorn api.index:app --reload --host 0.0.0.0 --port 8000
```

Or run the CLI:

```bash
python main.py
```

## Build Android APK

GitHub Actions workflow: **Build Sweep Acceptance Preview APK**.

The workflow:

1. runs Python regression tests;
2. smoke-tests the API engine;
3. compiles the native Android ZIP engine;
4. packages `public/index.html` as an offline APK asset;
5. verifies package, version, embedded dashboard, and APK signature;
6. uploads the APK artifact for 30 days.

The Android package is separate from Amy FX:

```text
com.amy.sweepacceptancelab.debug
```

## Important limits

This repository is a testing laboratory, not a claim that Sweep or Acceptance is profitable. A result is only meaningful when the same rule is tested with realistic costs, next-open execution, sufficient samples, and untouched out-of-sample data.
