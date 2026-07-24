import pandas as pd
import os
from config import CONFIG
from strategies import get_strategy
from engine.backtest import BacktestEngine
from analysis.report import generate_report

def main():
    print(f"Loading data from {CONFIG['data_path']}...")
    if not os.path.exists(CONFIG['data_path']):
        print(f"Error: Data file not found at {CONFIG['data_path']}")
        print("Please run generate_dummy_data.py first!")
        return
        
    df = pd.read_csv(CONFIG['data_path'])
    
    required_cols = ['timestamp', 'open', 'high', 'low', 'close', 'volume']
    if not all(col in df.columns for col in required_cols):
        print(f"Error: Data must contain columns: {required_cols}")
        return
        
    print(f"Initializing strategy: {CONFIG['strategy_name']}")
    strategy = get_strategy(CONFIG['strategy_name'], CONFIG['strategy_params'])
    
    print("Running backtest engine...")
    engine = BacktestEngine(df, strategy, CONFIG)
    trades, equity_curve = engine.run()
    
    output_dir = os.path.join(os.path.dirname(__file__), 'analysis', 'output')
    generate_report(trades, equity_curve, output_dir=output_dir)

if __name__ == "__main__":
    main()
