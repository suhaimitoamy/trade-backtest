# Python Backtest Engine (FastAPI + Vercel)

A modular, multi-strategy backtesting engine in Python suitable for Termux, designed to run as a **FastAPI backend on Vercel** or locally via CLI.

## Project Structure
- `api/index.py`: FastAPI serverless endpoint for Vercel.
- `data/`: CSV data folder. Data should have columns: `timestamp`, `open`, `high`, `low`, `close`, `volume`.
- `strategies/`: Folder for strategy files.
- `engine/backtest.py`: The core engine that processes strategies candle-by-candle.
- `analysis/report.py`: Analytics and equity curve generation (returns Base64 image & JSON).
- `config.py`: Main configuration for active strategy, data path, and risk management.
- `vercel.json`: Vercel routing configuration.
- `.github/workflows/backtest.yml`: GitHub Actions CI/CD configuration.

## How to Add a New Strategy
1. **Create a new file** in `strategies/` (e.g., `ict_smc.py`).
2. **Implement `BaseStrategy`**:
   ```python
   from .base import BaseStrategy

   class ICTStrategy(BaseStrategy):
       def detect_bias(self, data, current_idx):
           # Return 1 (Long), -1 (Short), or 0 (Neutral)
           pass
           
       def detect_entry(self, data, current_idx, bias):
           # Return True if entry condition is met
           pass
           
       def get_sl_tp(self, data, current_idx, bias):
           # Return tuple: (StopLoss, TakeProfit)
           pass
   ```
3. **Register your strategy** in `strategies/__init__.py`:
   ```python
   from .ict_smc import ICTStrategy
   STRATEGIES['ict_smc'] = ICTStrategy
   ```
4. **Update `config.py`** to use `'ict_smc'`.

## Running Locally (CLI)
1. Install requirements: `pip install -r requirements.txt`
2. Run data generation: `python generate_dummy_data.py`
3. Run the backtest: `python main.py`

## Running Locally (FastAPI API)
You can run the API server locally to test the Vercel integration:
1. `uvicorn api.index:app --reload --host 0.0.0.0 --port 8000`
2. Open `http://localhost:8000/api/backtest` in your browser.

## Deployment to Vercel
1. Generate your data first so it exists in the repo: `python generate_dummy_data.py`
2. Commit your code to GitHub.
3. Import the repository in Vercel. Vercel will automatically use `@vercel/python` thanks to `vercel.json` and serve `/api/backtest`.
