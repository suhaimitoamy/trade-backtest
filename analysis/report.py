import base64
import io
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


def _metrics(trades: pd.DataFrame, equity_curve: pd.DataFrame) -> dict:
    if trades.empty:
        return {
            "total_trades": 0,
            "winrate": 0.0,
            "profit_factor": 0.0,
            "expectancy_r": 0.0,
            "avg_r_multiple": 0.0,
            "total_r": 0.0,
            "total_pnl": 0.0,
            "max_drawdown": 0.0,
            "max_drawdown_r": 0.0,
        }

    r = pd.to_numeric(trades["r_multiple"], errors="coerce").fillna(0.0)
    gains = float(r[r > 0].sum())
    losses = float(-r[r < 0].sum())
    profit_factor = gains / losses if losses > 0 else (999.0 if gains > 0 else 0.0)

    equity = equity_curve.copy()
    equity["peak"] = equity["equity"].cummax()
    equity["drawdown"] = (equity["peak"] - equity["equity"]) / equity["peak"] * 100

    cumulative_r = r.cumsum()
    peak_r = cumulative_r.cummax()
    drawdown_r = peak_r - cumulative_r

    return {
        "total_trades": int(len(trades)),
        "winrate": round(float((r > 0).mean() * 100), 2),
        "profit_factor": round(float(profit_factor), 3),
        "expectancy_r": round(float(r.mean()), 4),
        "avg_r_multiple": round(float(r.mean()), 4),
        "total_r": round(float(r.sum()), 3),
        "total_pnl": round(float(trades["pnl_amount"].sum()), 2),
        "max_drawdown": round(float(equity["drawdown"].max()), 2),
        "max_drawdown_r": round(float(drawdown_r.max()), 3),
    }


def _breakdown(trades: pd.DataFrame, column: str) -> list[dict]:
    if trades.empty or column not in trades.columns:
        return []
    rows = []
    for name, group in trades.groupby(column, dropna=False):
        r = pd.to_numeric(group["r_multiple"], errors="coerce").fillna(0.0)
        gains = float(r[r > 0].sum())
        losses = float(-r[r < 0].sum())
        rows.append({
            "name": str(name),
            "trades": int(len(group)),
            "winrate": round(float((r > 0).mean() * 100), 2),
            "total_r": round(float(r.sum()), 3),
            "avg_r": round(float(r.mean()), 4),
            "profit_factor": round(gains / losses, 3) if losses > 0 else 999.0,
        })
    return sorted(rows, key=lambda item: item["name"])


def generate_report(trades: pd.DataFrame, equity_curve: pd.DataFrame, output_dir: str = "analysis/output"):
    os.makedirs(output_dir, exist_ok=True)
    metrics = _metrics(trades, equity_curve)
    print("=" * 44)
    print("BACKTEST REPORT")
    print("=" * 44)
    for key, value in metrics.items():
        print(f"{key:20}: {value}")

    if not equity_curve.empty:
        curve = equity_curve.copy()
        curve["timestamp"] = pd.to_datetime(curve["timestamp"], errors="coerce")
        plt.figure(figsize=(10, 6))
        plt.plot(curve["timestamp"], curve["equity"], label="Equity")
        plt.title("Equity Curve")
        plt.xlabel("Date")
        plt.ylabel("Capital")
        plt.legend()
        plt.tight_layout()
        plt.savefig(os.path.join(output_dir, "equity_curve.png"))
        plt.close()


def generate_report_json(trades: pd.DataFrame, equity_curve: pd.DataFrame) -> dict:
    metrics = _metrics(trades, equity_curve)
    chart_base64 = ""

    if not equity_curve.empty:
        curve = equity_curve.copy()
        curve["timestamp"] = pd.to_datetime(curve["timestamp"], errors="coerce")
        plt.figure(figsize=(10, 5))
        plt.plot(curve["timestamp"], curve["equity"], label="Equity")
        plt.title("Equity Curve")
        plt.xlabel("Date")
        plt.ylabel("Capital")
        plt.legend()
        plt.tight_layout()
        buf = io.BytesIO()
        plt.savefig(buf, format="png", dpi=120)
        buf.seek(0)
        chart_base64 = base64.b64encode(buf.read()).decode("utf-8")
        plt.close()

    preview_columns = [
        "signal_time", "entry_time", "exit_time", "direction", "setup",
        "entry_price", "exit_price", "sl", "tp", "bars_held",
        "r_multiple", "reason",
    ]
    preview = []
    if not trades.empty:
        available = [col for col in preview_columns if col in trades.columns]
        preview_df = trades[available].tail(100).copy()
        preview_df = preview_df.replace({np.nan: None})
        preview = preview_df.to_dict(orient="records")

    return {
        "metrics": metrics,
        "setup_breakdown": _breakdown(trades, "setup"),
        "direction_breakdown": _breakdown(trades, "direction"),
        "trades": preview,
        "chart_base64": chart_base64,
    }
