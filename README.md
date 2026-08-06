# Trading Method Lab

A modular Python backtest engine, FastAPI/Vercel dashboard, and installable Android application for testing fixed presets or building a trading method from no-code condition blocks.

## Android large-data mode

The APK contains an offline dashboard and native Java streaming engines. Candle archives are not uploaded to Vercel.

- Select multiple annual ZIP archives from Android storage or the Google Drive file provider.
- Supports CSV/TXT, ZIP containing monthly candle files, and annual ZIP containing nested monthly ZIP archives.
- Select one timeframe per run: M1, M5, M15, H1, H4, or D1.
- Data is read line by line; the complete multi-million-candle archive is never loaded into RAM.
- Indicator, pending signal, open position, equity, and drawdown state continue across monthly and annual file boundaries.
- Strict validation cancels a result when OHLC is invalid, timestamps are duplicated, or time moves backward.
- Signals use completed candles and enter at the next candle open.
- If SL and TP are touched in the same candle, the conservative `SL first` assumption is used.

The browser/Vercel API remains limited to small CSV requests. The native Android engine has no hard 250,000-candle limit.

## No-code Method Builder

APK v2.3 adds a separate native custom-method engine in `MethodBuilderBridge.java`.

The user can create independent BUY and SELL rule sets, choose `ALL` or `ANY` logic, mirror BUY rules to SELL, save methods locally on the device, and choose execution parameters.

Available condition blocks include:

- bullish or bearish candle;
- body, wick, and close-location ratios;
- candle range relative to ATR;
- EMA fast/slow trend and EMA slope;
- rolling-high/low breakout;
- sweep and reclaim;
- RBS retest and SBR retest;
- price position inside the rolling range;
- distance from rolling high/low in ATR;
- consecutive bullish/bearish candles;
- close beyond the previous candle;
- one-candle higher-high/higher-low or lower-high/lower-low structure;
- UTC session-hour range.

Custom Stop Loss modes:

- ATR only;
- signal-candle extreme with an ATR minimum;
- rolling high/low with an ATR minimum.

The builder executes only predefined, validated rule blocks. It does not execute arbitrary code and does not automatically optimize rules against historical results.

## Built-in Sweep / Acceptance preset

The original preset remains available:

- `SWEEP_LOW_RECLAIM` → Long after price trades below the rolling low and closes back above it.
- `SWEEP_HIGH_RECLAIM` → mirrored Short signal.
- `ACCEPTANCE_ABOVE` → Long after consecutive closes remain above the rolling high with body and close-location requirements.
- `ACCEPTANCE_BELOW` → mirrored Short signal.

## Results

The application reports:

- total trades and win rate;
- profit factor and expectancy in R;
- total R and monetary PnL;
- maximum drawdown;
- setup and direction breakdowns;
- compacted equity curve;
- the latest 100 trades.

## CSV format

```text
timestamp,open,high,low,close,volume
```

`volume` is optional. `date` + `time`, `datetime`, and `timestamp` formats are accepted. Comma, semicolon, and tab delimiters are detected automatically.

## Project structure

- `android-app/app/src/main/java/com/amy/tradebacktest/MethodBuilderBridge.java` — no-code custom rule evaluator and native streaming backtest engine.
- `android-app/app/src/main/java/com/amy/tradebacktest/LocalZipBacktestBridge.java` — built-in Sweep/Acceptance native engine.
- `android-app/app/src/main/java/com/amy/tradebacktest/MainActivity.java` — multi-file picker and JavaScript bridges.
- `public/index.html` — offline/mobile dashboard and Method Builder UI.
- `strategies/acceptance_sweep.py` — Python/API preset strategy.
- `engine/backtest.py` — Python next-open candle engine.
- `api/index.py` — FastAPI endpoint for small browser runs.
- `analysis/report.py` — Python metrics and charts.
- `tests/test_acceptance_sweep.py` — preset execution regression tests.

## Run Python locally

```bash
pip install -r requirements.txt pytest
pytest -q
uvicorn api.index:app --reload --host 0.0.0.0 --port 8000
```

## Build Android APK

GitHub Actions workflow: **Build Trading Method Lab Preview APK**.

The workflow runs Python tests and API smoke tests, compiles both native Android engines, packages the offline dashboard, verifies the builder UI and APK signature, and uploads an installable artifact.

Android package:

```text
com.amy.sweepacceptancelab.debug
```

## Important limitation

This repository is a testing laboratory, not a claim that any preset or user-built method is profitable. A result is meaningful only when costs, sample size, timeframe, untouched out-of-sample periods, and parameter stability are evaluated honestly.
