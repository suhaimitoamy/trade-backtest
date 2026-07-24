import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

CONFIG = {
    'data_path': os.path.join(BASE_DIR, 'data', 'dummy_data.csv'),
    'strategy_name': 'ema_cross',
    'strategy_params': {
        'fast_period': 9,
        'slow_period': 21,
        'rr_ratio': 2.0
    },
    'initial_capital': 10000,
    'risk_per_trade_pct': 1.0,  # 1% risk per trade
    'timeframe': '1H'
}
