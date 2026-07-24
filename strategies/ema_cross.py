import pandas as pd
from typing import Tuple
from .base import BaseStrategy

class EMACrossStrategy(BaseStrategy):
    def __init__(self, params=None):
        super().__init__(params)
        self.fast_period = self.params.get('fast_period', 9)
        self.slow_period = self.params.get('slow_period', 21)
        self.rr_ratio = self.params.get('rr_ratio', 2.0)
        
    def prepare_data(self, data: pd.DataFrame) -> pd.DataFrame:
        # Precompute indicators
        # We only use past data in ewm, so no look-ahead bias here
        df = data.copy()
        df['ema_fast'] = df['close'].ewm(span=self.fast_period, adjust=False).mean()
        df['ema_slow'] = df['close'].ewm(span=self.slow_period, adjust=False).mean()
        return df

    def detect_bias(self, data: pd.DataFrame, current_idx: int) -> int:
        if current_idx < self.slow_period:
            return 0
            
        ema_fast = data['ema_fast'].iloc[current_idx]
        ema_slow = data['ema_slow'].iloc[current_idx]
        
        if ema_fast > ema_slow:
            return 1 # Long bias
        elif ema_fast < ema_slow:
            return -1 # Short bias
        return 0

    def detect_entry(self, data: pd.DataFrame, current_idx: int, bias: int) -> bool:
        if current_idx < 1 or bias == 0:
            return False
            
        prev_ema_fast = data['ema_fast'].iloc[current_idx - 1]
        prev_ema_slow = data['ema_slow'].iloc[current_idx - 1]
        curr_ema_fast = data['ema_fast'].iloc[current_idx]
        curr_ema_slow = data['ema_slow'].iloc[current_idx]
        
        # Crossover
        if bias == 1 and prev_ema_fast <= prev_ema_slow and curr_ema_fast > curr_ema_slow:
            return True
        elif bias == -1 and prev_ema_fast >= prev_ema_slow and curr_ema_fast < curr_ema_slow:
            return True
            
        return False

    def get_sl_tp(self, data: pd.DataFrame, current_idx: int, bias: int) -> Tuple[float, float]:
        entry_price = data['close'].iloc[current_idx]
        
        # Simple SL based on recent extreme
        lookback = 5
        start_idx = max(0, current_idx - lookback)
        
        if bias == 1:
            sl = data['low'].iloc[start_idx:current_idx+1].min()
            if sl == entry_price:
                sl = entry_price * 0.999 # 0.1% fallback
            risk = entry_price - sl
            tp = entry_price + (risk * self.rr_ratio)
        else:
            sl = data['high'].iloc[start_idx:current_idx+1].max()
            if sl == entry_price:
                sl = entry_price * 1.001 # 0.1% fallback
            risk = sl - entry_price
            tp = entry_price - (risk * self.rr_ratio)
            
        return sl, tp
