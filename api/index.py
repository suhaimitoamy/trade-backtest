from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
import pandas as pd
import os
import sys

# Menambahkan parent direktori ke path agar bisa import dari folder luar 'api/'
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import CONFIG
from strategies import get_strategy
from engine.backtest import BacktestEngine
from analysis.report import generate_report_json

app = FastAPI(title="Trading Backtest API")

# Konfigurasi CORS agar bisa dipanggil dari Frontend web
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/")
def read_root():
    return {"message": "Backtest Engine API is running. Endpoint untuk backtest: /api/backtest"}

@app.get("/api/backtest")
def run_backtest():
    try:
        # Cek apakah file data tersedia
        if not os.path.exists(CONFIG['data_path']):
            # Karena di vercel read-only, kita handle gracefully
            return JSONResponse(status_code=404, content={"error": "Data file tidak ditemukan. Pastikan data/dummy_data.csv sudah di commit ke github."})
            
        df = pd.read_csv(CONFIG['data_path'])
        
        required_cols = ['timestamp', 'open', 'high', 'low', 'close', 'volume']
        if not all(col in df.columns for col in required_cols):
            return JSONResponse(status_code=400, content={"error": f"Data wajib punya kolom: {required_cols}"})
        
        # Load strategi
        strategy = get_strategy(CONFIG['strategy_name'], CONFIG['strategy_params'])
        
        # Jalankan engine
        engine = BacktestEngine(df, strategy, CONFIG)
        trades, equity_curve = engine.run()
        
        # Format hasil menjadi JSON
        result = generate_report_json(trades, equity_curve)
        return result
        
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})
