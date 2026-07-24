import pandas as pd
import numpy as np
import os
from datetime import datetime, timedelta

def generate_data(filepath, rows=2000):
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    
    np.random.seed(42)
    timestamps = [datetime(2023, 1, 1) + timedelta(hours=i) for i in range(rows)]
    
    # Generate random walk for returns
    returns = np.random.normal(0, 0.005, rows)
    close_prices = 1.1000 * np.exp(np.cumsum(returns))
    
    # Synthesize OHLC based on close
    volatility = 0.002
    open_prices = close_prices * (1 + np.random.normal(0, volatility, rows))
    high_prices = np.maximum(open_prices, close_prices) * (1 + abs(np.random.normal(0, volatility, rows)))
    low_prices = np.minimum(open_prices, close_prices) * (1 - abs(np.random.normal(0, volatility, rows)))
    
    # Ensure first row makes sense
    open_prices[0] = 1.1000
    
    df = pd.DataFrame({
        'timestamp': timestamps,
        'open': open_prices,
        'high': high_prices,
        'low': low_prices,
        'close': close_prices,
        'volume': np.random.randint(100, 1000, rows)
    })
    
    df.to_csv(filepath, index=False)
    print(f"Dummy data generated at {filepath} with {rows} rows.")

if __name__ == "__main__":
    filepath = os.path.join(os.path.dirname(__file__), 'data', 'dummy_data.csv')
    generate_data(filepath)
