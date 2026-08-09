import hashlib
import json
import os
import zipfile
from pathlib import Path

import pandas as pd


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def main() -> None:
    year = int(os.environ['YEAR'])
    src = Path(f'raw/XAUUSD_{year}_M1_DUKASCOPY_BID.csv')
    out = Path('output')
    out.mkdir(exist_ok=True)

    df = pd.read_csv(src)
    original_columns = list(df.columns)
    norm = {str(c).strip().lower().replace(' ', '_'): c for c in df.columns}

    def choose(names):
        for name in names:
            if name in norm:
                return norm[name]
        return None

    dt_col = choose(['datetime', 'timestamp', 'time', 'date_time', 'date'])
    o_col = choose(['open', 'bid_open'])
    h_col = choose(['high', 'bid_high'])
    l_col = choose(['low', 'bid_low'])
    c_col = choose(['close', 'bid_close'])
    if not all([dt_col, o_col, h_col, l_col, c_col]):
        raise SystemExit(f'Unsupported CSV columns: {original_columns}')

    work = df[[dt_col, o_col, h_col, l_col, c_col]].copy()
    work.columns = ['datetime', 'open', 'high', 'low', 'close']
    work['datetime'] = pd.to_datetime(work['datetime'], utc=True, errors='coerce')
    for col in ['open', 'high', 'low', 'close']:
        work[col] = pd.to_numeric(work[col], errors='coerce')

    malformed = int(work.isna().any(axis=1).sum())
    if malformed:
        raise SystemExit(f'Malformed/null source rows: {malformed}')

    work = work.sort_values('datetime', kind='stable').reset_index(drop=True)
    source_rows = len(work)
    exact_dup = int(work.duplicated(subset=['datetime', 'open', 'high', 'low', 'close']).sum())
    if exact_dup:
        work = work.drop_duplicates(subset=['datetime', 'open', 'high', 'low', 'close'], keep='first')

    dup_ts = work[work.duplicated(subset=['datetime'], keep=False)]
    conflicting = 0
    if len(dup_ts):
        for _, group in dup_ts.groupby('datetime', sort=False):
            if len(group[['open', 'high', 'low', 'close']].drop_duplicates()) > 1:
                conflicting += 1
    if conflicting:
        raise SystemExit(f'Conflicting duplicate timestamps: {conflicting}')
    work = work.drop_duplicates(subset=['datetime'], keep='first')

    invalid_ohlc = int(((work['high'] < work[['open', 'close']].max(axis=1)) |
                        (work['low'] > work[['open', 'close']].min(axis=1)) |
                        (work['high'] < work['low'])).sum())
    if invalid_ohlc:
        raise SystemExit(f'Invalid OHLC rows: {invalid_ohlc}')

    work = work.set_index('datetime')
    if work.empty:
        raise SystemExit('No M1 candles returned')

    frames = {'M1': work[['open', 'high', 'low', 'close']].copy()}
    for tf, rule in {'M5': '5min', 'M15': '15min', 'H1': '1h', 'H4': '4h', 'D1': '1D'}.items():
        frame = work.resample(rule, origin='start_day', label='left', closed='left').agg(
            {'open': 'first', 'high': 'max', 'low': 'min', 'close': 'last'}
        )
        frames[tf] = frame.dropna(subset=['open', 'high', 'low', 'close'])

    month_archives = []
    audit_rows = []
    for month in range(1, 13):
        observed_m1 = frames['M1'][(frames['M1'].index.year == year) & (frames['M1'].index.month == month)]
        if observed_m1.empty:
            continue
        temp_files = []
        for tf in ['M1', 'M5', 'M15', 'H1', 'H4', 'D1']:
            frame = frames[tf]
            monthly = frame[(frame.index.year == year) & (frame.index.month == month)].reset_index()
            monthly['datetime'] = monthly['datetime'].dt.strftime('%Y-%m-%d %H:%M:%S+00:00')
            path = out / f'XAUUSD_{year}_{month:02d}_{tf}.csv'
            monthly.to_csv(path, index=False, columns=['datetime', 'open', 'high', 'low', 'close'])
            temp_files.append(path)
            audit_rows.append({
                'year': year,
                'month': month,
                'timeframe': tf,
                'rows': len(monthly),
                'first': monthly['datetime'].iloc[0] if len(monthly) else '',
                'last': monthly['datetime'].iloc[-1] if len(monthly) else '',
            })

        month_zip = out / f'XAUUSD_{year}_{month:02d}_MULTITF_REPAIRED_AUDITED.zip'
        with zipfile.ZipFile(month_zip, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path in temp_files:
                archive.write(path, path.name)
        month_archives.append(month_zip)
        for path in temp_files:
            path.unlink()

    if not month_archives:
        raise SystemExit('No monthly archives created')

    audit_path = out / f'XAUUSD_{year}_AGGREGATION_AUDIT.csv'
    pd.DataFrame(audit_rows).to_csv(audit_path, index=False)

    first = work.index.min().isoformat()
    last = work.index.max().isoformat()
    clean_rows = len(work)
    report = f'''XAUUSD MULTI-TIMEFRAME DATASET — {year}\n\nSOURCE\n- Provider: Dukascopy historical service\n- Instrument: XAUUSD\n- Quote side: BID\n- Source timeframe: M1\n- Retrieval engine: Dukascopy Jetta JSON via dukascopy-go v0.1.5\n- Timestamp normalization: UTC\n\nOUTPUT\n- Timeframes: M1, M5, M15, H1, H4, D1\n- CSV schema: datetime,open,high,low,close\n- Monthly archives created: {len(month_archives)}\n- First observed M1: {first}\n- Last observed M1: {last}\n\nAUDIT RESULTS\n- Source rows inspected: {source_rows}\n- Clean unique M1 candles retained: {clean_rows}\n- Exact duplicate rows removed: {exact_dup}\n- Conflicting duplicate timestamps: {conflicting}\n- Malformed/null rows: {malformed}\n- Invalid OHLC rows: {invalid_ohlc}\n\nIMPORTANT\n- No interpolation.\n- No forward fill.\n- No synthetic candles.\n- Upstream gaps are preserved.\n- Higher timeframes are aggregated only from observed M1 candles.\n'''
    report_path = out / f'XAUUSD_{year}_AGGREGATION_REPORT.txt'
    report_path.write_text(report, encoding='utf-8')

    manifest = {
        'year': year,
        'provider': 'Dukascopy historical service',
        'retrieval_engine': 'Jetta JSON via dukascopy-go v0.1.5',
        'instrument': 'XAUUSD',
        'side': 'BID',
        'source_timeframe': 'M1',
        'source_rows': source_rows,
        'clean_m1_rows': clean_rows,
        'first_m1_utc': first,
        'last_m1_utc': last,
        'no_interpolation': True,
        'no_forward_fill': True,
        'monthly_archives': [
            {'name': path.name, 'sha256': sha256(path), 'bytes': path.stat().st_size}
            for path in month_archives
        ],
        'raw_source_sha256': sha256(src),
    }
    manifest_path = out / f'XAUUSD_{year}_AGGREGATION_MANIFEST.json'
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')

    yearly_zip = out / f'XAUUSD_{year}_MONTHLY_ARCHIVES_REPAIRED_AUDITED.zip'
    with zipfile.ZipFile(yearly_zip, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in month_archives:
            archive.write(path, path.name)
        for path in [audit_path, report_path, manifest_path]:
            archive.write(path, path.name)

    for path in month_archives:
        path.unlink()

    print(report)
    print(f'YEARLY_PACKAGE={yearly_zip} bytes={yearly_zip.stat().st_size} sha256={sha256(yearly_zip)}')


if __name__ == '__main__':
    main()
