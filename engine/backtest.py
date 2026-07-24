import pandas as pd
from typing import Dict, Any

class BacktestEngine:
    def __init__(self, data: pd.DataFrame, strategy: Any, config: Dict[str, Any]):
        self.data = strategy.prepare_data(data.copy())
        self.strategy = strategy
        self.config = config
        self.trades = []
        self.equity_curve = []
        
    def run(self):
        initial_capital = self.config.get('initial_capital', 10000)
        risk_per_trade = self.config.get('risk_per_trade_pct', 1.0) / 100.0
        
        capital = initial_capital
        self.equity_curve.append({'timestamp': self.data['timestamp'].iloc[0], 'equity': capital})
        
        in_trade = False
        trade_dir = 0
        entry_price = 0.0
        sl = 0.0
        tp = 0.0
        entry_time = None
        
        # Loop candle by candle
        for i in range(len(self.data)):
            row = self.data.iloc[i]
            timestamp = row['timestamp']
            
            # Check for exits if in trade
            if in_trade:
                high = row['high']
                low = row['low']
                
                exit_price = None
                reason = None
                
                if trade_dir == 1:
                    if low <= sl:
                        exit_price = sl
                        reason = 'SL'
                    elif high >= tp:
                        exit_price = tp
                        reason = 'TP'
                elif trade_dir == -1:
                    if high >= sl:
                        exit_price = sl
                        reason = 'SL'
                    elif low <= tp:
                        exit_price = tp
                        reason = 'TP'
                        
                if exit_price is not None:
                    # Calculate PnL based on R-multiple
                    if trade_dir == 1:
                        r_multiple = (exit_price - entry_price) / (entry_price - sl) if entry_price != sl else 0
                    else:
                        r_multiple = (entry_price - exit_price) / (sl - entry_price) if sl != entry_price else 0
                        
                    # Calculate position size and PnL
                    risk_amount = capital * risk_per_trade
                    pnl_amount = risk_amount * r_multiple
                    capital += pnl_amount
                    
                    self.trades.append({
                        'entry_time': entry_time,
                        'exit_time': timestamp,
                        'direction': 'Long' if trade_dir == 1 else 'Short',
                        'entry_price': entry_price,
                        'exit_price': exit_price,
                        'sl': sl,
                        'tp': tp,
                        'pnl_amount': pnl_amount,
                        'r_multiple': r_multiple,
                        'reason': reason
                    })
                    
                    self.equity_curve.append({'timestamp': timestamp, 'equity': capital})
                    in_trade = False
                    
            # If not in trade, check for entry signals
            if not in_trade:
                bias = self.strategy.detect_bias(self.data, i)
                if bias != 0:
                    entry = self.strategy.detect_entry(self.data, i, bias)
                    if entry:
                        sl, tp = self.strategy.get_sl_tp(self.data, i, bias)
                        entry_price = row['close']
                        trade_dir = bias
                        entry_time = timestamp
                        in_trade = True
                        
        return pd.DataFrame(self.trades), pd.DataFrame(self.equity_curve)
