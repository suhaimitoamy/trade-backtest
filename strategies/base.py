from abc import ABC, abstractmethod
import pandas as pd
from typing import Dict, Any, Tuple

class BaseStrategy(ABC):
    def __init__(self, params: Dict[str, Any] = None):
        self.params = params or {}
        
    @abstractmethod
    def detect_bias(self, data: pd.DataFrame, current_idx: int) -> int:
        """
        Detects the current market bias.
        :param data: The historical dataframe up to current_idx.
        :param current_idx: Current candle index.
        :return: 1 for Long, -1 for Short, 0 for Neutral.
        """
        pass

    @abstractmethod
    def detect_entry(self, data: pd.DataFrame, current_idx: int, bias: int) -> bool:
        """
        Detects if there is an entry signal at the current candle.
        :param data: The historical dataframe up to current_idx.
        :param current_idx: Current candle index.
        :param bias: The bias from detect_bias.
        :return: True if entry condition is met, False otherwise.
        """
        pass

    @abstractmethod
    def get_sl_tp(self, data: pd.DataFrame, current_idx: int, bias: int) -> Tuple[float, float]:
        """
        Calculates the Stop Loss and Take Profit levels for the entry.
        :param data: The historical dataframe up to current_idx.
        :param current_idx: Current candle index.
        :param bias: The bias from detect_bias.
        :return: Tuple of (Stop Loss price, Take Profit price).
        """
        pass
        
    def prepare_data(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        Optional: pre-calculate indicators on the whole dataset to avoid look-ahead bias
        but speed up computation. Must use shifting appropriately if used.
        """
        return data
