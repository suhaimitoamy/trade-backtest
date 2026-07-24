import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import os
import io
import base64

def generate_report(trades: pd.DataFrame, equity_curve: pd.DataFrame, output_dir: str = 'analysis/output'):
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
        
    print("="*40)
    print("BACKTEST REPORT")
    print("="*40)
    
    if trades.empty:
        print("No trades executed.")
        return
        
    total_trades = len(trades)
    winning_trades = trades[trades['pnl_amount'] > 0]
    winrate = len(winning_trades) / total_trades * 100
    
    total_pnl = trades['pnl_amount'].sum()
    avg_r = trades['r_multiple'].mean()
    expectancy = trades['pnl_amount'].mean()
    
    equity_curve['peak'] = equity_curve['equity'].cummax()
    equity_curve['drawdown'] = (equity_curve['peak'] - equity_curve['equity']) / equity_curve['peak'] * 100
    max_drawdown = equity_curve['drawdown'].max()
    
    print(f"Total Trades : {total_trades}")
    print(f"Winrate      : {winrate:.2f}%")
    print(f"Expectancy   : ${expectancy:.2f}")
    print(f"Avg R-Mult   : {avg_r:.2f}R")
    print(f"Total PnL    : ${total_pnl:.2f}")
    print(f"Max Drawdown : {max_drawdown:.2f}%")
    print("="*40)
    
    try:
        trades['entry_time'] = pd.to_datetime(trades['entry_time'])
        trades['year'] = trades['entry_time'].dt.year
        
        trades['hour'] = trades['entry_time'].dt.hour
        def get_session(h):
            if 1 <= h < 8: return 'Asian'
            elif 8 <= h < 14: return 'London'
            elif 14 <= h < 21: return 'New York'
            else: return 'Sydney'
        trades['session'] = trades['hour'].apply(get_session)
        
        yearly = trades.groupby('year').agg(
            Trades=('pnl_amount', 'count'),
            PnL=('pnl_amount', 'sum'),
            Avg_R=('r_multiple', 'mean')
        )
        print("\nYearly Breakdown:")
        print(yearly)
        
        session = trades.groupby('session').agg(
            Trades=('pnl_amount', 'count'),
            PnL=('pnl_amount', 'sum'),
            Avg_R=('r_multiple', 'mean')
        )
        print("\nSession Breakdown:")
        print(session)
        
    except Exception as e:
        print(f"Could not generate breakdown: {e}")
        
    plt.figure(figsize=(10, 6))
    equity_curve['timestamp'] = pd.to_datetime(equity_curve['timestamp'])
    plt.plot(equity_curve['timestamp'], equity_curve['equity'], label='Equity', color='blue')
    plt.title('Equity Curve')
    plt.xlabel('Date')
    plt.ylabel('Capital ($)')
    plt.grid(True, alpha=0.3)
    plt.legend()
    
    plot_path = os.path.join(output_dir, 'equity_curve.png')
    plt.savefig(plot_path)
    plt.close()
    print(f"\nEquity curve saved to: {plot_path}")

def generate_report_json(trades: pd.DataFrame, equity_curve: pd.DataFrame) -> dict:
    """
    Format report output for Vercel/API response.
    Avoids writing any files to disk.
    """
    if trades.empty:
        return {"error": "No trades executed."}
        
    total_trades = len(trades)
    winning_trades = trades[trades['pnl_amount'] > 0]
    winrate = len(winning_trades) / total_trades * 100
    
    total_pnl = trades['pnl_amount'].sum()
    avg_r = trades['r_multiple'].mean()
    expectancy = trades['pnl_amount'].mean()
    
    equity_curve['peak'] = equity_curve['equity'].cummax()
    equity_curve['drawdown'] = (equity_curve['peak'] - equity_curve['equity']) / equity_curve['peak'] * 100
    max_drawdown = equity_curve['drawdown'].max()
    
    # Generate Plot into Memory Buffer
    plt.figure(figsize=(10, 6))
    if 'timestamp' in equity_curve.columns:
        equity_curve['timestamp'] = pd.to_datetime(equity_curve['timestamp'])
        plt.plot(equity_curve['timestamp'], equity_curve['equity'], label='Equity', color='blue')
    plt.title('Equity Curve')
    plt.xlabel('Date')
    plt.ylabel('Capital ($)')
    plt.grid(True, alpha=0.3)
    plt.legend()
    
    buf = io.BytesIO()
    plt.savefig(buf, format='png')
    buf.seek(0)
    img_base64 = base64.b64encode(buf.read()).decode('utf-8')
    plt.close()
    
    return {
        "metrics": {
            "total_trades": total_trades,
            "winrate": round(winrate, 2),
            "expectancy": round(expectancy, 2),
            "avg_r_multiple": round(avg_r, 2),
            "total_pnl": round(total_pnl, 2),
            "max_drawdown": round(max_drawdown, 2)
        },
        "chart_base64": img_base64
    }
