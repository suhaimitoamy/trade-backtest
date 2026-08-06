from __future__ import annotations

from typing import Any, Dict, Optional
import pandas as pd


class BacktestEngine:
    """Single-position candle engine with next-open entry and conservative fills."""

    def __init__(self, data: pd.DataFrame, strategy: Any, config: Dict[str, Any]):
        self.data = strategy.prepare_data(data.copy()).reset_index(drop=True)
        self.strategy = strategy
        self.config = config
        self.trades: list[dict[str, Any]] = []
        self.equity_curve: list[dict[str, Any]] = []

    def _exit_result(
        self,
        direction: int,
        entry: float,
        sl: float,
        tp: float,
        high: float,
        low: float,
    ) -> tuple[Optional[float], Optional[str]]:
        if direction == 1:
            hit_sl, hit_tp = low <= sl, high >= tp
        else:
            hit_sl, hit_tp = high >= sl, low <= tp

        if hit_sl and hit_tp:
            policy = str(self.config.get("same_bar_policy", "sl_first"))
            return (tp, "TP") if policy == "tp_first" else (sl, "SL")
        if hit_sl:
            return sl, "SL"
        if hit_tp:
            return tp, "TP"
        return None, None

    @staticmethod
    def _gross_r(
        direction: int, entry: float, exit_price: float, sl: float
    ) -> float:
        risk = entry - sl if direction == 1 else sl - entry
        if risk <= 0:
            return 0.0
        if direction == 1:
            return (exit_price - entry) / risk
        return (entry - exit_price) / risk

    def run(self):
        if self.data.empty:
            return pd.DataFrame(), pd.DataFrame()

        initial_capital = float(self.config.get("initial_capital", 10000))
        risk_per_trade = float(self.config.get("risk_per_trade_pct", 1.0)) / 100.0
        max_hold_bars = int(self.config.get("max_hold_bars", 24))
        cost_r = max(0.0, float(self.config.get("cost_per_trade_r", 0.0)))

        capital = initial_capital
        first_time = self.data["timestamp"].iloc[0] if "timestamp" in self.data else 0
        self.equity_curve.append(
            {"timestamp": first_time, "equity": capital, "cumulative_r": 0.0}
        )
        cumulative_r = 0.0

        trade: Optional[Dict[str, Any]] = None
        pending: Optional[Dict[str, Any]] = None

        def close_trade(
            exit_price: float, reason: str, timestamp: Any, exit_idx: int
        ) -> None:
            nonlocal trade, capital, cumulative_r
            assert trade is not None
            gross_r = self._gross_r(
                trade["direction"], trade["entry_price"], exit_price, trade["sl"]
            )
            net_r = gross_r - cost_r
            risk_amount = capital * risk_per_trade
            pnl_amount = risk_amount * net_r
            capital += pnl_amount
            cumulative_r += net_r

            self.trades.append(
                {
                    "signal_time": trade["signal_time"],
                    "entry_time": trade["entry_time"],
                    "exit_time": timestamp,
                    "direction": "Long" if trade["direction"] == 1 else "Short",
                    "setup": trade["setup"],
                    "entry_price": trade["entry_price"],
                    "exit_price": float(exit_price),
                    "sl": trade["sl"],
                    "tp": trade["tp"],
                    "bars_held": int(exit_idx - trade["entry_idx"] + 1),
                    "gross_r": gross_r,
                    "cost_r": cost_r,
                    "r_multiple": net_r,
                    "pnl_amount": pnl_amount,
                    "reason": reason,
                }
            )
            self.equity_curve.append(
                {
                    "timestamp": timestamp,
                    "equity": capital,
                    "cumulative_r": cumulative_r,
                }
            )
            trade = None

        for i in range(len(self.data)):
            row = self.data.iloc[i]
            timestamp = row["timestamp"] if "timestamp" in self.data else i

            # Signals are created on a completed candle. Entry is the next candle open.
            if trade is None and pending is not None:
                entry_price = float(row["open"])
                plan = self.strategy.get_trade_plan(
                    self.data,
                    pending["signal_idx"],
                    pending["bias"],
                    entry_price,
                )
                sl, tp = float(plan["sl"]), float(plan["tp"])
                valid = (
                    pending["bias"] == 1 and sl < entry_price < tp
                ) or (
                    pending["bias"] == -1 and tp < entry_price < sl
                )
                if valid:
                    trade = {
                        "signal_time": pending["signal_time"],
                        "entry_time": timestamp,
                        "entry_idx": i,
                        "direction": pending["bias"],
                        "entry_price": entry_price,
                        "sl": sl,
                        "tp": tp,
                        "setup": str(plan.get("label", pending["label"])),
                    }
                pending = None

            # The entry candle is allowed to hit SL or TP after its open.
            if trade is not None:
                exit_price, reason = self._exit_result(
                    trade["direction"],
                    trade["entry_price"],
                    trade["sl"],
                    trade["tp"],
                    float(row["high"]),
                    float(row["low"]),
                )
                if exit_price is not None:
                    close_trade(float(exit_price), str(reason), timestamp, i)
                elif (
                    max_hold_bars > 0
                    and i - trade["entry_idx"] + 1 >= max_hold_bars
                ):
                    close_trade(float(row["close"]), "TIME", timestamp, i)

            # A new signal may be queued only when no position or pending order exists.
            if trade is None and pending is None and i < len(self.data) - 1:
                bias = int(self.strategy.detect_bias(self.data, i))
                if bias != 0 and self.strategy.detect_entry(self.data, i, bias):
                    pending = {
                        "signal_idx": i,
                        "signal_time": timestamp,
                        "bias": bias,
                        "label": self.strategy.get_signal_label(
                            self.data, i, bias
                        ),
                    }

        if trade is not None:
            last_idx = len(self.data) - 1
            last = self.data.iloc[last_idx]
            timestamp = last["timestamp"] if "timestamp" in self.data else last_idx
            close_trade(float(last["close"]), "DATA_END", timestamp, last_idx)

        return pd.DataFrame(self.trades), pd.DataFrame(self.equity_curve)
