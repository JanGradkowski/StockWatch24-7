#!/usr/bin/env python3
"""Build the frozen 2019-2025 candlestick validation input.

Universe selection is made without post-2018 outcomes: US equities in the local
pre-2019 metadata snapshot with at least $300m market cap, plus the final S&P 500
membership snapshot on or before 2018-12-31. Yahoo daily history is downloaded
from 2017 for indicator/trend warm-up, adjusted to the adjusted-close basis,
cached per symbol, and written as deterministic gzip CSV.
"""

from __future__ import annotations

import argparse
import csv
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import date, datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import random
import re
import time
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


DEFAULT_METADATA = Path("target/expanded-backtest-data/us_equities_meta_data.csv")
DEFAULT_SP500 = Path("target/expanded-backtest-data/sp500_historical.csv")
DEFAULT_CACHE = Path("target/expanded-backtest-data/post-2018-yahoo-cache")
DEFAULT_PARQUET = Path("target/expanded-backtest-data/hexquant-stocks-daily")
DEFAULT_MANIFEST = Path(
    "target/expanded-backtest-data/post-2018-candlestick-universe.tsv"
)
DEFAULT_OUTPUT = Path(
    "target/expanded-backtest-data/post-2018-candles.csv.gz"
)
DEFAULT_FAILURES = Path(
    "target/expanded-backtest-data/post-2018-download-failures.csv"
)
START_DATE = date(2017, 1, 1)
END_DATE_EXCLUSIVE = date(2026, 1, 1)
MINIMUM_MARKET_CAP = 300_000_000.0
MINIMUM_CANDLES = 250
SYMBOL_PATTERN = re.compile(r"^[A-Z][A-Z0-9.-]{0,9}$")
EXCLUDED_NAME_PATTERN = re.compile(
    r"(?:Warrant|Preferred|Depositary|\sUnit(?:s)?\b|Notes?\b|Bonds?\b|ETF\b|Fund\b)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class UniverseItem:
    symbol: str
    name: str
    market_cap: float | None
    source: str


@dataclass(frozen=True)
class DownloadResult:
    item: UniverseItem
    candles: tuple[tuple[str, float, float, float, float, int], ...]
    yahoo_symbol: str
    error: str | None = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare the frozen 2019-2025 candlestick validation dataset."
    )
    parser.add_argument("--metadata", type=Path, default=DEFAULT_METADATA)
    parser.add_argument("--sp500-history", type=Path, default=DEFAULT_SP500)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE)
    parser.add_argument(
        "--parquet-dir",
        type=Path,
        default=DEFAULT_PARQUET,
        help="Hugging Face Stocks-Daily-Price Parquet directory; preferred over Yahoo.",
    )
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--failures", type=Path, default=DEFAULT_FAILURES)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--maximum-symbols", type=int)
    parser.add_argument("--refresh", action="store_true")
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest().upper()


def load_universe(metadata_path: Path, sp500_path: Path) -> list[UniverseItem]:
    selected: dict[str, UniverseItem] = {}
    with metadata_path.open("r", encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            symbol = row["ticker"].strip().upper()
            name = row["name"].strip()
            if not SYMBOL_PATTERN.fullmatch(symbol) or EXCLUDED_NAME_PATTERN.search(name):
                continue
            try:
                market_cap = float(row["marketcap"])
            except (TypeError, ValueError):
                continue
            if market_cap < MINIMUM_MARKET_CAP:
                continue
            selected[symbol] = UniverseItem(
                symbol, name or symbol, market_cap, "PRE_2019_MARKET_CAP"
            )

    membership_date = ""
    membership: set[str] = set()
    with sp500_path.open("r", encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            row_date = row["date"]
            if row_date <= "2018-12-31" and row_date >= membership_date:
                membership_date = row_date
                membership = {
                    value.strip().upper()
                    for value in row["tickers"].split(",")
                    if value.strip()
                }
    for symbol in membership:
        if not SYMBOL_PATTERN.fullmatch(symbol):
            continue
        existing = selected.get(symbol)
        if existing is None:
            selected[symbol] = UniverseItem(symbol, symbol, None, "SP500_2018")
        else:
            selected[symbol] = UniverseItem(
                existing.symbol,
                existing.name,
                existing.market_cap,
                "PRE_2019_MARKET_CAP+SP500_2018",
            )
    result = sorted(selected.values(), key=lambda item: item.symbol)
    if len(result) < 2_000:
        raise ValueError(f"Pre-outcome universe unexpectedly small: {len(result):,}")
    return result


def yahoo_symbol(symbol: str) -> str:
    return symbol.replace(".", "-")


def epoch(value: date) -> int:
    return int(datetime(value.year, value.month, value.day, tzinfo=timezone.utc).timestamp())


def cache_path(cache_dir: Path, symbol: str) -> Path:
    safe = re.sub(r"[^A-Z0-9_-]", "_", symbol)
    return cache_dir / f"{safe}.json.gz"


def fetch_payload(item: UniverseItem, cache_dir: Path, refresh: bool) -> tuple[dict, str]:
    mapped = yahoo_symbol(item.symbol)
    cached = cache_path(cache_dir, item.symbol)
    if cached.exists() and not refresh:
        with gzip.open(cached, "rt", encoding="utf-8") as source:
            return json.load(source), mapped

    url = (
        "https://query1.finance.yahoo.com/v8/finance/chart/"
        f"{quote(mapped, safe='')}?period1={epoch(START_DATE)}"
        f"&period2={epoch(END_DATE_EXCLUSIVE)}&interval=1d"
        "&events=div%2Csplits&includeAdjustedClose=true"
    )
    request = Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 StockWatch247 research validation",
            "Accept": "application/json",
        },
    )
    last_error: Exception | None = None
    for attempt in range(4):
        try:
            with urlopen(request, timeout=30) as response:
                payload = json.load(response)
            cache_dir.mkdir(parents=True, exist_ok=True)
            with gzip.open(cached, "wt", encoding="utf-8", newline="\n") as target:
                json.dump(payload, target, separators=(",", ":"), sort_keys=True)
            return payload, mapped
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as error:
            last_error = error
            if isinstance(error, HTTPError) and error.code == 404:
                break
            time.sleep((2**attempt) + random.random())
    raise RuntimeError(str(last_error) if last_error else "Unknown Yahoo failure")


def parse_candles(payload: dict) -> tuple[tuple[str, float, float, float, float, int], ...]:
    chart = payload.get("chart") or {}
    if chart.get("error"):
        raise ValueError(str(chart["error"]))
    results = chart.get("result") or []
    if not results:
        raise ValueError("Yahoo response has no chart result")
    result = results[0]
    timestamps = result.get("timestamp") or []
    quote_rows = ((result.get("indicators") or {}).get("quote") or [])
    adjusted_rows = ((result.get("indicators") or {}).get("adjclose") or [])
    if not quote_rows:
        raise ValueError("Yahoo response has no OHLC rows")
    quote_values = quote_rows[0]
    adjusted_values = adjusted_rows[0].get("adjclose", []) if adjusted_rows else []
    candles: list[tuple[str, float, float, float, float, int]] = []
    for index, timestamp in enumerate(timestamps):
        try:
            raw_open = float(quote_values["open"][index])
            raw_high = float(quote_values["high"][index])
            raw_low = float(quote_values["low"][index])
            raw_close = float(quote_values["close"][index])
            volume = int(quote_values["volume"][index] or 0)
            adjusted_close = (
                float(adjusted_values[index])
                if index < len(adjusted_values) and adjusted_values[index] is not None
                else raw_close
            )
        except (KeyError, IndexError, TypeError, ValueError):
            continue
        if min(raw_open, raw_high, raw_low, raw_close, adjusted_close) <= 0:
            continue
        ratio = adjusted_close / raw_close
        adjusted_open = raw_open * ratio
        adjusted_high = raw_high * ratio
        adjusted_low = raw_low * ratio
        if adjusted_high < max(adjusted_open, adjusted_close) or adjusted_low > min(
            adjusted_open, adjusted_close
        ):
            continue
        row_date = datetime.fromtimestamp(int(timestamp), timezone.utc).date().isoformat()
        candles.append(
            (
                row_date,
                adjusted_open,
                adjusted_high,
                adjusted_low,
                adjusted_close,
                max(0, volume),
            )
        )
    candles.sort(key=lambda row: row[0])
    deduplicated = tuple({row[0]: row for row in candles}.values())
    if len(deduplicated) < MINIMUM_CANDLES:
        raise ValueError(f"Only {len(deduplicated)} usable daily candles")
    return deduplicated


def download(item: UniverseItem, cache_dir: Path, refresh: bool) -> DownloadResult:
    try:
        payload, mapped = fetch_payload(item, cache_dir, refresh)
        return DownloadResult(item, parse_candles(payload), mapped)
    except Exception as error:  # preserve every failed symbol in the audit output
        return DownloadResult(item, (), yahoo_symbol(item.symbol), str(error))


def write_outputs(
    results: Iterable[DownloadResult],
    manifest_path: Path,
    output_path: Path,
    failures_path: Path,
) -> None:
    ordered = sorted(results, key=lambda result: result.item.symbol)
    successes = [result for result in ordered if result.candles]
    failures = [result for result in ordered if not result.candles]
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with manifest_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, delimiter="\t", lineterminator="\n")
        writer.writerow(
            (
                "symbol",
                "yahoo_symbol",
                "name",
                "selection_source",
                "market_cap_usd_pre_2019",
                "first_date",
                "last_date",
                "daily_candles",
            )
        )
        for result in successes:
            writer.writerow(
                (
                    result.item.symbol,
                    result.yahoo_symbol,
                    result.item.name,
                    result.item.source,
                    "" if result.item.market_cap is None else f"{result.item.market_cap:.0f}",
                    result.candles[0][0],
                    result.candles[-1][0],
                    len(result.candles),
                )
            )
    with gzip.open(output_path, "wt", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, lineterminator="\n")
        writer.writerow(("ticker", "date", "open", "high", "low", "close", "volume"))
        for result in successes:
            for row in result.candles:
                writer.writerow((result.item.symbol, *row))
    with failures_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, lineterminator="\n")
        writer.writerow(("symbol", "yahoo_symbol", "selection_source", "error"))
        for result in failures:
            writer.writerow(
                (result.item.symbol, result.yahoo_symbol, result.item.source, result.error)
            )
    candle_count = sum(len(result.candles) for result in successes)
    print(
        f"Prepared {len(successes):,}/{len(ordered):,} symbols and "
        f"{candle_count:,} adjusted daily candles; failures={len(failures):,}."
    )
    print(f"Manifest SHA-256: {sha256(manifest_path)}")
    print(f"Candle file SHA-256: {sha256(output_path)}")


def write_parquet_outputs(
    universe: list[UniverseItem],
    parquet_dir: Path,
    manifest_path: Path,
    output_path: Path,
    failures_path: Path,
) -> None:
    try:
        import duckdb
    except ImportError as error:
        raise RuntimeError(
            "DuckDB is required for Parquet preparation: python -m pip install duckdb"
        ) from error
    shards = sorted(parquet_dir.glob("train-*.parquet"))
    if len(shards) != 4:
        raise ValueError(f"Expected four Parquet shards in {parquet_dir}, found {len(shards)}")
    connection = duckdb.connect()
    connection.execute(
        "create temp table selected_symbols(symbol varchar, name varchar, "
        "market_cap double, selection_source varchar)"
    )
    connection.executemany(
        "insert into selected_symbols values (?, ?, ?, ?)",
        [
            (item.symbol, item.name, item.market_cap, item.source)
            for item in universe
        ],
    )
    parquet_glob = str((parquet_dir / "train-*.parquet").resolve()).replace("\\", "/")
    coverage_rows = connection.execute(
        f"""
        select selected_symbols.symbol, any_value(name), any_value(market_cap),
               any_value(selection_source), min(cast(date as date)), max(cast(date as date)),
               count(*)
          from read_parquet('{parquet_glob}') prices
          join selected_symbols on selected_symbols.symbol = upper(prices.symbol)
         where cast(date as date) >= date '2017-01-01'
           and cast(date as date) < date '2026-01-01'
           and open > 0 and high > 0 and low > 0 and close > 0 and adj_close > 0
           and high >= greatest(open, close) and low <= least(open, close)
         group by selected_symbols.symbol
         order by selected_symbols.symbol
        """
    ).fetchall()
    coverage = {row[0]: row for row in coverage_rows if int(row[6]) >= MINIMUM_CANDLES}
    failures = [item for item in universe if item.symbol not in coverage]
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with manifest_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, delimiter="\t", lineterminator="\n")
        writer.writerow(
            (
                "symbol",
                "name",
                "selection_source",
                "market_cap_usd_pre_2019",
                "first_date",
                "last_date",
                "daily_candles",
                "price_source",
            )
        )
        for symbol in sorted(coverage):
            row = coverage[symbol]
            writer.writerow(
                (
                    symbol,
                    row[1],
                    row[3],
                    "" if row[2] is None else f"{row[2]:.0f}",
                    row[4].isoformat(),
                    row[5].isoformat(),
                    row[6],
                    "HexQuant/Stocks-Daily-Price",
                )
            )
    eligible_symbols = sorted(coverage)
    connection.execute("create temp table eligible_symbols(symbol varchar)")
    connection.executemany(
        "insert into eligible_symbols values (?)", [(symbol,) for symbol in eligible_symbols]
    )
    rows = connection.execute(
        f"""
        select upper(prices.symbol), cast(date as date), open, high, low, close, volume, adj_close
          from read_parquet('{parquet_glob}') prices
          join eligible_symbols on eligible_symbols.symbol = upper(prices.symbol)
         where cast(date as date) >= date '2017-01-01'
           and cast(date as date) < date '2026-01-01'
           and open > 0 and high > 0 and low > 0 and close > 0 and adj_close > 0
           and high >= greatest(open, close) and low <= least(open, close)
         order by upper(prices.symbol), cast(date as date)
        """
    )
    written = 0
    skipped = 0
    with gzip.open(output_path, "wt", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, lineterminator="\n")
        writer.writerow(("ticker", "date", "open", "high", "low", "close", "volume"))
        while batch := rows.fetchmany(50_000):
            for symbol, row_date, raw_open, raw_high, raw_low, raw_close, volume, adj_close in batch:
                try:
                    ratio = float(adj_close) / float(raw_close)
                    adjusted_open = float(raw_open) * ratio
                    adjusted_high = float(raw_high) * ratio
                    adjusted_low = float(raw_low) * ratio
                    adjusted_close = float(adj_close)
                    # Multiplying raw high/low and using the provider's separately
                    # rounded adjusted close can differ by a few ULPs. Clamp only
                    # after the SQL-level raw-OHLC validity gate so those harmless
                    # rounding differences cannot make an impossible candle.
                    adjusted_high = max(adjusted_high, adjusted_open, adjusted_close)
                    adjusted_low = min(adjusted_low, adjusted_open, adjusted_close)
                    writer.writerow(
                        (
                            symbol,
                            row_date.isoformat(),
                            adjusted_open,
                            adjusted_high,
                            adjusted_low,
                            adjusted_close,
                            max(0, int(volume or 0)),
                        )
                    )
                    written += 1
                except (TypeError, ValueError, ZeroDivisionError):
                    skipped += 1
    with failures_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, lineterminator="\n")
        writer.writerow(("symbol", "selection_source", "error"))
        for item in failures:
            writer.writerow((item.symbol, item.source, "Missing or fewer than 250 valid candles"))
    connection.close()
    print(
        f"Prepared {len(eligible_symbols):,}/{len(universe):,} symbols and "
        f"{written:,} adjusted daily candles from Parquet; "
        f"failures={len(failures):,}, skipped rows={skipped:,}."
    )
    print(f"Manifest SHA-256: {sha256(manifest_path)}")
    print(f"Candle file SHA-256: {sha256(output_path)}")


def main() -> None:
    args = parse_args()
    universe = load_universe(args.metadata, args.sp500_history)
    if args.maximum_symbols is not None:
        universe = universe[: max(1, args.maximum_symbols)]
    print(f"Pre-outcome universe contains {len(universe):,} symbols.")
    if args.parquet_dir.exists():
        write_parquet_outputs(
            universe, args.parquet_dir, args.manifest, args.output, args.failures
        )
        return
    results: list[DownloadResult] = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(download, item, args.cache_dir, args.refresh): item
            for item in universe
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            results.append(future.result())
            if completed % 100 == 0 or completed == len(futures):
                successes = sum(bool(result.candles) for result in results)
                print(
                    f"Yahoo history processed {completed:,}/{len(futures):,}; "
                    f"usable={successes:,}."
                )
    write_outputs(results, args.manifest, args.output, args.failures)


if __name__ == "__main__":
    main()
