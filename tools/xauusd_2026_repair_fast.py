#!/usr/bin/env python3
from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import timedelta

import xauusd_2026_repair as repair


def main() -> int:
    dates = []
    current = repair.START
    while current <= repair.END:
        dates.append(current)
        current += timedelta(days=1)

    cache: dict[str, tuple[str, bytes, int | None]] = {}

    def download(day):
        url = repair.url_for(day)
        result = repair.fetch(url)
        return day, url, result

    with ThreadPoolExecutor(max_workers=12) as pool:
        futures = [pool.submit(download, day) for day in dates]
        for future in as_completed(futures):
            day, url, result = future.result()
            cache[url] = result
            status, body, http = result
            print(json.dumps({
                "prefetch_date_utc": day.isoformat(),
                "status": status,
                "http_status": http,
                "bytes": len(body),
            }), flush=True)

    original_fetch = repair.fetch

    def cached_fetch(url: str, retries: int = 5):
        if url not in cache:
            return original_fetch(url, retries=retries)
        return cache[url]

    repair.fetch = cached_fetch
    return repair.main()


if __name__ == "__main__":
    raise SystemExit(main())
