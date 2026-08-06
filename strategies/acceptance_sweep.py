from __future__ import annotations

from typing import Any, Dict, Optional, Tuple
import numpy as np
import pandas as pd

from .base import BaseStrategy


class AcceptanceSweepStrategy(BaseStrategy):
    """Objective rolling-level test for sweep rejection and acceptance breakout.

    The reference level is always built from completed candles before the signal
    sequence. Signals are evaluated on candle close and the engine enters on the
    next candle open.
    """

    VALID_MODES = {"both", "sweep_only", "acceptance_only"}

    def __init__(self, params: Optional[Dict[str, Any]] = None):
        super().__init__(params)
        self.lookback = int(self.params.get("lookback", 20))
        self.atr_period = int(self.params.get("atr_period", 14))
        self.mode = str(self.params.get("mode", "both"))
        self.min_penetration_atr = float(self.params.get("min_penetration_atr", 0.05))
        self.min_wick_ratio = float(self.params.get("min_wick_ratio", 0.35))
        self.acceptance_closes = int(self.params.get("acceptance_closes", 2))
        self.min_body_ratio = float(self.params.get("min_body_ratio", 0.55))
        self.close_location = float(self.params.get("close_location", 0.70))
        self.sl_atr = float(self.params.get("sl_atr", 1.0))
        self.rr_ratio = float(self.params.get("rr_ratio", 2.0))
        self.stop_buffer_atr = float(self.params.get("stop_buffer_atr", 0.10))

        if self.mode not in self.VALID_MODES:
            raise ValueError(f"mode must be one of {sorted(self.VALID_MODES)}")
        if self.lookback < 3 or self.atr_period < 2:
            raise ValueError("lookback and atr_period are too small")
        if self.acceptance_closes < 1 or self.acceptance_closes > 5:
            raise ValueError("acceptance_closes must be between 1 and 5")
        if self.rr_ratio <= 0 or self.sl_atr <= 0:
            raise ValueError("rr_ratio and sl_atr must be positive")

    def prepare_data(self, data: pd.DataFrame) -> pd.DataFrame:
        df = data.copy()
        for col in ("open", "high", "low", "close"):
            df[col] = pd.to_numeric(df[col], errors="coerce")

        prev_close = df["close"].shift(1)
        true_range = pd.concat(
            [
                df["high"] - df["low"],
                (df["high"] - prev_close).abs(),
                (df["low"] - prev_close).abs(),
            ],
            axis=1,
        ).max(axis=1)
        df["atr"] = true_range.rolling(
            self.atr_period, min_periods=self.atr_period
        ).mean()
        df["prior_high"] = (
            df["high"].shift(1).rolling(self.lookback, min_periods=self.lookback).max()
        )
        df["prior_low"] = (
            df["low"].shift(1).rolling(self.lookback, min_periods=self.lookback).min()
        )
        return df

    @staticmethod
    def _ratios(row: pd.Series) -> Tuple[float, float, float, float]:
        candle_range = float(row["high"] - row["low"])
        if not np.isfinite(candle_range) or candle_range <= 0:
            return 0.0, 0.0, 0.0, 0.5
        body = abs(float(row["close"] - row["open"])) / candle_range
        upper_wick = (
            float(row["high"] - max(row["open"], row["close"])) / candle_range
        )
        lower_wick = (
            float(min(row["open"], row["close"]) - row["low"]) / candle_range
        )
        close_loc = float(row["close"] - row["low"]) / candle_range
        return body, upper_wick, lower_wick, close_loc

    def _sweep_signal(
        self, data: pd.DataFrame, idx: int
    ) -> Optional[Dict[str, Any]]:
        row = data.iloc[idx]
        atr = float(row.get("atr", np.nan))
        prior_high = float(row.get("prior_high", np.nan))
        prior_low = float(row.get("prior_low", np.nan))
        if not all(np.isfinite(v) for v in (atr, prior_high, prior_low)) or atr <= 0:
            return None

        _, upper_wick, lower_wick, _ = self._ratios(row)
        penetration = self.min_penetration_atr * atr

        bullish = (
            float(row["low"]) < prior_low - penetration
            and float(row["close"]) > prior_low
            and lower_wick >= self.min_wick_ratio
        )
        bearish = (
            float(row["high"]) > prior_high + penetration
            and float(row["close"]) < prior_high
            and upper_wick >= self.min_wick_ratio
        )

        if bullish and not bearish:
            return {
                "bias": 1,
                "label": "SWEEP_LOW_RECLAIM",
                "level": prior_low,
                "atr": atr,
            }
        if bearish and not bullish:
            return {
                "bias": -1,
                "label": "SWEEP_HIGH_RECLAIM",
                "level": prior_high,
                "atr": atr,
            }
        return None

    def _acceptance_signal(
        self, data: pd.DataFrame, idx: int
    ) -> Optional[Dict[str, Any]]:
        n = self.acceptance_closes
        first_idx = idx - n + 1
        if first_idx < 1:
            return None

        first = data.iloc[first_idx]
        last = data.iloc[idx]
        atr = float(first.get("atr", np.nan))
        high_level = float(first.get("prior_high", np.nan))
        low_level = float(first.get("prior_low", np.nan))
        if not all(np.isfinite(v) for v in (atr, high_level, low_level)) or atr <= 0:
            return None

        closes = data["close"].iloc[first_idx : idx + 1].astype(float)
        prior_close = float(data["close"].iloc[first_idx - 1])
        buffer = self.min_penetration_atr * atr
        body_ratio, _, _, close_loc = self._ratios(last)

        bullish = (
            prior_close <= high_level + buffer
            and bool((closes > high_level + buffer).all())
            and body_ratio >= self.min_body_ratio
            and close_loc >= self.close_location
        )
        bearish = (
            prior_close >= low_level - buffer
            and bool((closes < low_level - buffer).all())
            and body_ratio >= self.min_body_ratio
            and close_loc <= 1.0 - self.close_location
        )

        if bullish and not bearish:
            return {
                "bias": 1,
                "label": "ACCEPTANCE_ABOVE",
                "level": high_level,
                "atr": atr,
            }
        if bearish and not bullish:
            return {
                "bias": -1,
                "label": "ACCEPTANCE_BELOW",
                "level": low_level,
                "atr": atr,
            }
        return None

    def signal_at(self, data: pd.DataFrame, idx: int) -> Optional[Dict[str, Any]]:
        warmup = max(self.lookback, self.atr_period) + self.acceptance_closes
        if idx < warmup:
            return None

        if self.mode in {"both", "sweep_only"}:
            sweep = self._sweep_signal(data, idx)
            if sweep is not None:
                return sweep
        if self.mode in {"both", "acceptance_only"}:
            return self._acceptance_signal(data, idx)
        return None

    def detect_bias(self, data: pd.DataFrame, current_idx: int) -> int:
        signal = self.signal_at(data, current_idx)
        return int(signal["bias"]) if signal else 0

    def detect_entry(self, data: pd.DataFrame, current_idx: int, bias: int) -> bool:
        signal = self.signal_at(data, current_idx)
        return bool(signal and int(signal["bias"]) == int(bias))

    def get_signal_label(self, data: pd.DataFrame, current_idx: int, bias: int) -> str:
        signal = self.signal_at(data, current_idx)
        return str(signal["label"]) if signal else "UNKNOWN"

    def get_trade_plan(
        self,
        data: pd.DataFrame,
        current_idx: int,
        bias: int,
        entry_price: float,
    ) -> Dict[str, Any]:
        signal = self.signal_at(data, current_idx)
        if not signal or int(signal["bias"]) != int(bias):
            raise ValueError("Signal is no longer valid")

        row = data.iloc[current_idx]
        atr = float(signal["atr"])
        buffer = self.stop_buffer_atr * atr
        label = str(signal["label"])

        if bias == 1:
            structural_sl = (
                float(row["low"]) - buffer
                if label.startswith("SWEEP")
                else float(signal["level"]) - buffer
            )
            sl = min(structural_sl, entry_price - self.sl_atr * atr)
            risk = entry_price - sl
            tp = entry_price + self.rr_ratio * risk
        else:
            structural_sl = (
                float(row["high"]) + buffer
                if label.startswith("SWEEP")
                else float(signal["level"]) + buffer
            )
            sl = max(structural_sl, entry_price + self.sl_atr * atr)
            risk = sl - entry_price
            tp = entry_price - self.rr_ratio * risk

        if not all(np.isfinite(v) for v in (sl, tp, risk)) or risk <= 0:
            raise ValueError("Invalid SL/TP plan")
        return {"sl": float(sl), "tp": float(tp), "label": label}

    def get_sl_tp(
        self, data: pd.DataFrame, current_idx: int, bias: int
    ) -> Tuple[float, float]:
        entry_price = float(data["close"].iloc[current_idx])
        plan = self.get_trade_plan(data, current_idx, bias, entry_price)
        return float(plan["sl"]), float(plan["tp"])
