#!/usr/bin/env python3
"""Prepare the frozen adjusted-OHLC input for the expanded candlestick study.

The source archive is intentionally not committed. This script verifies the
exact public Quandl WIKI archive used by the study, selects only the frozen
manifest, and writes a deterministic gzip CSV under target/.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
from pathlib import Path
import sys
import zipfile
from datetime import date as calendar_date


ARCHIVE_SHA256 = "ADFD226694C6F3EC2C56B585D973764180A39A3EC516601721E90096DC1DE94F"
CSV_SHA256 = "CA7FB174C7948DB85638917D25FF65D438E27D5CB23675DA784C54DB01E3D003"
CSV_MEMBER = "WIKI_PRICES.csv"
START_DATE = "2003-03-28"
END_DATE = "2018-03-27"
OUTPUT_COLUMNS = ("ticker", "date", "open", "high", "low", "close", "volume")
POWER_SYMBOLS = 2_193
POWER_DAILY_CANDLES = 7_802_979
POWER_ENDED_SYMBOLS = 400
POWER_MANIFEST_SHA256 = (
    "8364BE9B75B3D91A3377FB128195849CAD080189FE928243AC92F6CE573F160F"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare the adjusted candle subset for the expanded validation."
    )
    parser.add_argument(
        "--archive",
        type=Path,
        required=True,
        help="Path to the Kaggle Quandl WIKI archive.zip file.",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(
            "src/test/resources/backtest/expanded-candlestick-universe.tsv"
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("target/expanded-backtest-data/expanded-candles.csv.gz"),
    )
    parser.add_argument(
        "--skip-source-hash",
        action="store_true",
        help="Development escape hatch only; never use for a published validation.",
    )
    parser.add_argument(
        "--build-power-universe",
        action="store_true",
        help=(
            "Build the frozen long-history power-validation manifest at --manifest "
            "before preparing candles. The 150-symbol core manifest is excluded."
        ),
    )
    parser.add_argument(
        "--core-manifest",
        type=Path,
        default=Path(
            "src/test/resources/backtest/expanded-candlestick-universe.tsv"
        ),
    )
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest().upper()


def load_manifest(path: Path) -> dict[str, int]:
    with path.open("r", encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    if not rows:
        raise ValueError(f"Manifest is empty: {path}")

    expected: dict[str, int] = {}
    for row in rows:
        symbol = row["symbol"]
        if symbol in expected:
            raise ValueError(f"Duplicate manifest symbol: {symbol}")
        expected[symbol] = int(row["daily_candles"])
    return expected


def build_power_manifest(
    archive: Path,
    core_manifest: Path,
    output: Path,
) -> None:
    core_symbols = set(load_manifest(core_manifest))
    if len(core_symbols) != 150:
        raise ValueError(
            f"Expected 150 core symbols, received {len(core_symbols)}."
        )

    coverage: dict[str, dict[str, object]] = {}
    with zipfile.ZipFile(archive) as zipped:
        with zipped.open(CSV_MEMBER) as binary_source:
            source = io.TextIOWrapper(binary_source, encoding="utf-8", newline="")
            for row in csv.DictReader(source):
                symbol = row["ticker"]
                row_date = row["date"]
                if (
                    symbol in core_symbols
                    or row_date < START_DATE
                    or row_date > END_DATE
                ):
                    continue

                item = coverage.setdefault(
                    symbol,
                    {
                        "first": row_date,
                        "last": row_date,
                        "count": 0,
                        "bad": False,
                    },
                )
                item["first"] = min(str(item["first"]), row_date)
                item["last"] = max(str(item["last"]), row_date)
                item["count"] = int(item["count"]) + 1
                try:
                    parse_valid_prices(row)
                except (TypeError, ValueError):
                    item["bad"] = True

    eligible: list[tuple[str, str, str, int]] = []
    for symbol, item in coverage.items():
        first = str(item["first"])
        last = str(item["last"])
        candle_count = int(item["count"])
        history_days = (
            calendar_date.fromisoformat(last) - calendar_date.fromisoformat(first)
        ).days
        if (
            candle_count >= 2_400
            and not bool(item["bad"])
            and history_days >= 3_650
        ):
            eligible.append((symbol, first, last, candle_count))
    eligible.sort()

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, delimiter="\t", lineterminator="\n")
        writer.writerow(
            (
                "symbol",
                "name",
                "sector",
                "cap_tier",
                "cohort",
                "role",
                "first_date",
                "last_date",
                "daily_candles",
                "market_cap_usd",
            )
        )
        for symbol, first, last, candle_count in eligible:
            cohort = (
                "ENDED_BEFORE_2018"
                if last < "2018-03-01"
                else "THROUGH_2018_ENDPOINT"
            )
            writer.writerow(
                (
                    symbol,
                    symbol,
                    "POWER_UNSTRATIFIED",
                    "POWER",
                    cohort,
                    "VALIDATION",
                    first,
                    last,
                    candle_count,
                    "",
                )
            )

    ended = sum(1 for _, _, last, _ in eligible if last < "2018-03-01")
    total_candles = sum(item[3] for item in eligible)
    actual_hash = sha256_file(output)
    if (
        len(eligible) != POWER_SYMBOLS
        or total_candles != POWER_DAILY_CANDLES
        or ended != POWER_ENDED_SYMBOLS
        or actual_hash != POWER_MANIFEST_SHA256
    ):
        raise ValueError(
            "Power-universe freeze mismatch: "
            f"symbols={len(eligible)}, candles={total_candles}, ended={ended}, "
            f"sha256={actual_hash}."
        )
    print(
        "Built power-validation manifest: "
        f"{len(eligible):,} symbols, {total_candles:,} candles, "
        f"{ended:,} ended tickers."
    )


def validate_source(archive: Path, skip_hash: bool) -> None:
    if not archive.is_file():
        raise FileNotFoundError(archive)
    if skip_hash:
        print("WARNING: source checksum verification was skipped.", file=sys.stderr)
        return

    actual_archive_hash = sha256_file(archive)
    if actual_archive_hash != ARCHIVE_SHA256:
        raise ValueError(
            "Unexpected source archive SHA-256. "
            f"Expected {ARCHIVE_SHA256}, received {actual_archive_hash}."
        )

    with zipfile.ZipFile(archive) as zipped:
        with zipped.open(CSV_MEMBER) as source:
            digest = hashlib.sha256()
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    actual_csv_hash = digest.hexdigest().upper()
    if actual_csv_hash != CSV_SHA256:
        raise ValueError(
            "Unexpected WIKI_PRICES.csv SHA-256. "
            f"Expected {CSV_SHA256}, received {actual_csv_hash}."
        )


def parse_valid_prices(row: dict[str, str]) -> tuple[float, float, float, float]:
    values = (
        float(row["adj_open"]),
        float(row["adj_high"]),
        float(row["adj_low"]),
        float(row["adj_close"]),
    )
    open_price, high_price, low_price, close_price = values
    if min(values) <= 0.0:
        raise ValueError("Adjusted OHLC contains a non-positive value.")
    if high_price + 1e-9 < max(open_price, close_price):
        raise ValueError("Adjusted high is below the candle body.")
    if low_price - 1e-9 > min(open_price, close_price):
        raise ValueError("Adjusted low is above the candle body.")
    return values


def write_subset(
    archive: Path,
    output: Path,
    expected_counts: dict[str, int],
) -> tuple[int, str]:
    output.parent.mkdir(parents=True, exist_ok=True)
    counts = {symbol: 0 for symbol in expected_counts}
    last_key: tuple[str, str] | None = None
    total = 0

    with zipfile.ZipFile(archive) as zipped:
        with zipped.open(CSV_MEMBER) as binary_source:
            source = io.TextIOWrapper(binary_source, encoding="utf-8", newline="")
            reader = csv.DictReader(source)
            with output.open("wb") as raw_output:
                with gzip.GzipFile(
                    filename="",
                    mode="wb",
                    fileobj=raw_output,
                    mtime=0,
                ) as compressed:
                    text_output = io.TextIOWrapper(
                        compressed,
                        encoding="utf-8",
                        newline="",
                    )
                    writer = csv.DictWriter(
                        text_output,
                        fieldnames=OUTPUT_COLUMNS,
                        lineterminator="\n",
                    )
                    writer.writeheader()

                    for row in reader:
                        symbol = row["ticker"]
                        date = row["date"]
                        if (
                            symbol not in expected_counts
                            or date < START_DATE
                            or date > END_DATE
                        ):
                            continue

                        key = (symbol, date)
                        if last_key is not None and key <= last_key:
                            raise ValueError(
                                "Source rows are not uniquely ordered by ticker/date: "
                                f"{last_key} then {key}."
                            )
                        last_key = key
                        parse_valid_prices(row)
                        writer.writerow(
                            {
                                "ticker": symbol,
                                "date": date,
                                "open": row["adj_open"],
                                "high": row["adj_high"],
                                "low": row["adj_low"],
                                "close": row["adj_close"],
                                "volume": str(round(float(row["adj_volume"]))),
                            }
                        )
                        counts[symbol] += 1
                        total += 1

                    text_output.flush()

    mismatches = {
        symbol: (expected_counts[symbol], actual)
        for symbol, actual in counts.items()
        if actual != expected_counts[symbol]
    }
    if mismatches:
        preview = list(sorted(mismatches.items()))[:10]
        raise ValueError(f"Manifest/data candle-count mismatch: {preview}")
    return total, sha256_file(output)


def main() -> int:
    args = parse_args()
    validate_source(args.archive, args.skip_source_hash)
    if args.build_power_universe:
        build_power_manifest(args.archive, args.core_manifest, args.manifest)
    expected_counts = load_manifest(args.manifest)
    total, output_hash = write_subset(args.archive, args.output, expected_counts)
    expected_total = sum(expected_counts.values())
    if total != expected_total:
        raise ValueError(
            f"Expected {expected_total:,} prepared candles, received {total:,}."
        )

    print(f"Prepared {total:,} adjusted daily candles for {len(expected_counts)} symbols.")
    print(f"Output: {args.output}")
    print(f"Output SHA-256: {output_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
