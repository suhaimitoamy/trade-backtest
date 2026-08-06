from __future__ import annotations

from io import StringIO
import os
import sys
from typing import Any, Dict, Optional

import pandas as pd
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from analysis.report import generate_report_json
from config import CONFIG
from engine.backtest import BacktestEngine
from strategies import get_strategy


app = FastAPI(title="Acceptance & Sweep Backtest API", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)

MAX_CSV_CHARS = 4_000_000
MAX_ROWS = 250_000


class BacktestRequest(BaseModel):
    csv_text: Optional[str] = Field(
        default=None,
        description="Optional CSV text. Uses repository demo data when omitted.",
    )
    strategy_params: Dict[str, Any] = Field(default_factory=dict)
    engine_params: Dict[str, Any] = Field(default_factory=dict)


def _normalise_data(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        raise ValueError("CSV tidak memiliki candle.")

    df = df.copy()
    df.columns = [str(col).strip().lower().replace(" ", "_") for col in df.columns]

    if "timestamp" not in df.columns:
        for alias in ("datetime", "date_time", "time_stamp"):
            if alias in df.columns:
                df = df.rename(columns={alias: "timestamp"})
                break

    if "timestamp" not in df.columns and "date" in df.columns and "time" in df.columns:
        df["timestamp"] = df["date"].astype(str) + " " + df["time"].astype(str)
    elif "timestamp" not in df.columns and "date" in df.columns:
        df["timestamp"] = df["date"].astype(str)

    required = ["open", "high", "low", "close"]
    missing = [col for col in required if col not in df.columns]
    if missing:
        raise ValueError(f"Kolom OHLC tidak lengkap: {missing}")

    for col in required:
        df[col] = pd.to_numeric(df[col], errors="coerce")

    if "timestamp" not in df.columns:
        df["timestamp"] = range(len(df))
    else:
        parsed = pd.to_datetime(df["timestamp"], errors="coerce", utc=True)
        if parsed.notna().mean() > 0.95:
            df["timestamp"] = parsed
            df = df.sort_values("timestamp")

    if "volume" not in df.columns:
        df["volume"] = 0.0
    else:
        df["volume"] = pd.to_numeric(df["volume"], errors="coerce").fillna(0.0)

    df = df.dropna(subset=required).reset_index(drop=True)
    if len(df) < 50:
        raise ValueError("Minimal 50 candle diperlukan.")
    if len(df) > MAX_ROWS:
        raise ValueError(
            f"Maksimal {MAX_ROWS:,} candle per proses aplikasi. Pecah data menjadi beberapa periode."
        )
    return df[["timestamp", "open", "high", "low", "close", "volume"]]


def _load_data(csv_text: Optional[str]) -> tuple[pd.DataFrame, str]:
    if csv_text is not None and csv_text.strip():
        if len(csv_text) > MAX_CSV_CHARS:
            raise ValueError("File CSV terlalu besar untuk request aplikasi/Vercel.")
        return _normalise_data(pd.read_csv(StringIO(csv_text))), "uploaded_csv"

    path = CONFIG["data_path"]
    if not os.path.exists(path):
        raise FileNotFoundError("Data demo tidak ditemukan di repository.")
    return _normalise_data(pd.read_csv(path)), os.path.basename(path)


def _run(request: BacktestRequest) -> dict:
    data, source = _load_data(request.csv_text)

    strategy_params = dict(CONFIG.get("strategy_params", {}))
    strategy_params.update(request.strategy_params or {})

    engine_config = {
        key: value
        for key, value in CONFIG.items()
        if key not in {"strategy_name", "strategy_params", "data_path"}
    }
    engine_config.update(request.engine_params or {})
    engine_config["same_bar_policy"] = "sl_first"

    strategy = get_strategy("acceptance_sweep", strategy_params)
    trades, equity_curve = BacktestEngine(data, strategy, engine_config).run()
    result = generate_report_json(trades, equity_curve)
    result["run"] = {
        "strategy": "acceptance_sweep",
        "data_source": source,
        "candles": int(len(data)),
        "first_timestamp": str(data["timestamp"].iloc[0]),
        "last_timestamp": str(data["timestamp"].iloc[-1]),
        "strategy_params": strategy_params,
        "engine_params": engine_config,
        "execution": "signal_on_close_entry_next_open",
        "same_bar_policy": "sl_first",
    }
    return result


@app.get("/")
def read_root():
    return {
        "message": "Acceptance & Sweep Backtest API aktif.",
        "endpoints": ["/api/defaults", "/api/backtest"],
    }


@app.get("/api/defaults")
def defaults():
    return {
        "strategy_params": CONFIG["strategy_params"],
        "engine_params": {
            "initial_capital": CONFIG["initial_capital"],
            "risk_per_trade_pct": CONFIG["risk_per_trade_pct"],
            "max_hold_bars": CONFIG["max_hold_bars"],
            "cost_per_trade_r": CONFIG["cost_per_trade_r"],
        },
        "limits": {"max_rows": MAX_ROWS, "max_csv_chars": MAX_CSV_CHARS},
    }


@app.get("/api/backtest")
def run_default_backtest():
    try:
        return _run(BacktestRequest())
    except Exception as exc:
        return JSONResponse(status_code=400, content={"error": str(exc)})


@app.post("/api/backtest")
def run_custom_backtest(request: BacktestRequest):
    try:
        return _run(request)
    except ValueError as exc:
        return JSONResponse(status_code=400, content={"error": str(exc)})
    except Exception as exc:
        return JSONResponse(status_code=500, content={"error": str(exc)})
