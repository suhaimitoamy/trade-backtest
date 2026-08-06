import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

CONFIG = {
    "data_path": os.path.join(BASE_DIR, "data", "dummy_data.csv"),
    "strategy_name": "acceptance_sweep",
    "strategy_params": {
        "mode": "both",
        "lookback": 20,
        "atr_period": 14,
        "min_penetration_atr": 0.05,
        "min_wick_ratio": 0.35,
        "acceptance_closes": 2,
        "min_body_ratio": 0.55,
        "close_location": 0.70,
        "sl_atr": 1.0,
        "stop_buffer_atr": 0.10,
        "rr_ratio": 2.0,
    },
    "initial_capital": 10000,
    "risk_per_trade_pct": 1.0,
    "max_hold_bars": 24,
    "cost_per_trade_r": 0.03,
    "same_bar_policy": "sl_first",
    "timeframe": "M5",
}
