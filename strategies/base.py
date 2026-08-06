from abc import ABC, abstractmethod
from typing import Any, Dict, Tuple
import pandas as pd


class BaseStrategy(ABC):
    def __init__(self, params: Dict[str, Any] | None = None):
        self.params = params or {}

    @abstractmethod
    def detect_bias(self, data: pd.DataFrame, current_idx: int) -> int:
        """Return 1 for Long, -1 for Short, or 0 for Neutral."""
        pass

    @abstractmethod
    def detect_entry(self, data: pd.DataFrame, current_idx: int, bias: int) -> bool:
        """Return True when a completed candle produces an entry signal."""
        pass

    @abstractmethod
    def get_sl_tp(self, data: pd.DataFrame, current_idx: int, bias: int) -> Tuple[float, float]:
        """Return absolute Stop Loss and Take Profit prices."""
        pass

    def prepare_data(self, data: pd.DataFrame) -> pd.DataFrame:
        """Pre-calculate features using completed data only."""
        return data

    def get_signal_label(self, data: pd.DataFrame, current_idx: int, bias: int) -> str:
        return self.__class__.__name__

    def get_trade_plan(
        self,
        data: pd.DataFrame,
        current_idx: int,
        bias: int,
        entry_price: float,
    ) -> Dict[str, Any]:
        """Build a trade plan after the next candle open is known.

        Existing strategies remain compatible through get_sl_tp(). New strategies may
        override this method so gap/open differences are reflected in risk and target.
        """
        sl, tp = self.get_sl_tp(data, current_idx, bias)
        return {
            "sl": float(sl),
            "tp": float(tp),
            "label": self.get_signal_label(data, current_idx, bias),
        }
