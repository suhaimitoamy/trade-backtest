import pandas as pd

from engine.backtest import BacktestEngine
from strategies.acceptance_sweep import AcceptanceSweepStrategy


def frame(rows):
    df = pd.DataFrame(
        rows,
        columns=["timestamp", "open", "high", "low", "close"],
    )
    df["volume"] = 1
    return df


def base_rows(n=30, price=100.0):
    rows = []
    for i in range(n):
        open_price = price + (i % 3) * 0.02
        rows.append(
            (
                i,
                open_price,
                open_price + 0.40,
                open_price - 0.40,
                open_price + 0.05,
            )
        )
    return rows


def test_bullish_sweep_enters_next_open():
    rows = base_rows()
    rows.append((30, 100.0, 100.2, 98.8, 99.9))
    rows.append((31, 100.1, 102.5, 99.8, 102.0))
    data = frame(rows)

    strategy = AcceptanceSweepStrategy(
        {
            "lookback": 20,
            "atr_period": 5,
            "mode": "sweep_only",
            "min_penetration_atr": 0.05,
            "min_wick_ratio": 0.5,
            "sl_atr": 0.5,
            "rr_ratio": 1.0,
        }
    )
    trades, _ = BacktestEngine(
        data,
        strategy,
        {
            "initial_capital": 10000,
            "risk_per_trade_pct": 1,
            "max_hold_bars": 5,
            "cost_per_trade_r": 0,
        },
    ).run()

    assert len(trades) == 1
    assert trades.iloc[0]["setup"] == "SWEEP_LOW_RECLAIM"
    assert trades.iloc[0]["entry_time"] == 31
    assert trades.iloc[0]["entry_price"] == 100.1


def test_bullish_acceptance_requires_consecutive_closes():
    rows = base_rows()
    rows.append((30, 100.2, 101.2, 100.1, 100.9))
    rows.append((31, 100.8, 102.0, 100.7, 101.9))
    rows.append((32, 102.0, 104.0, 101.8, 103.8))
    data = frame(rows)

    strategy = AcceptanceSweepStrategy(
        {
            "lookback": 20,
            "atr_period": 5,
            "mode": "acceptance_only",
            "acceptance_closes": 2,
            "min_penetration_atr": 0.01,
            "min_body_ratio": 0.5,
            "close_location": 0.7,
            "sl_atr": 0.5,
            "rr_ratio": 1.0,
        }
    )
    prepared = strategy.prepare_data(data)
    assert strategy.get_signal_label(prepared, 31, 1) == "ACCEPTANCE_ABOVE"

    trades, _ = BacktestEngine(
        data,
        strategy,
        {
            "initial_capital": 10000,
            "risk_per_trade_pct": 1,
            "max_hold_bars": 5,
            "cost_per_trade_r": 0,
        },
    ).run()

    assert len(trades) == 1
    assert trades.iloc[0]["entry_time"] == 32
    assert trades.iloc[0]["setup"] == "ACCEPTANCE_ABOVE"


def test_same_bar_uses_conservative_sl_first():
    rows = base_rows()
    rows.append((30, 100.0, 100.2, 98.8, 99.9))
    rows.append((31, 100.0, 105.0, 95.0, 100.0))
    data = frame(rows)

    strategy = AcceptanceSweepStrategy(
        {
            "lookback": 20,
            "atr_period": 5,
            "mode": "sweep_only",
            "min_penetration_atr": 0.05,
            "min_wick_ratio": 0.5,
            "sl_atr": 0.5,
            "rr_ratio": 1.0,
        }
    )
    trades, _ = BacktestEngine(
        data,
        strategy,
        {
            "initial_capital": 10000,
            "risk_per_trade_pct": 1,
            "max_hold_bars": 5,
            "cost_per_trade_r": 0,
            "same_bar_policy": "sl_first",
        },
    ).run()

    assert trades.iloc[0]["reason"] == "SL"
    assert trades.iloc[0]["r_multiple"] == -1.0
