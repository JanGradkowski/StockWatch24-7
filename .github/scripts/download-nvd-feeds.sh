#!/usr/bin/env bash
# Downloads the NVD JSON 2.0 data feeds that OWASP dependency-check needs into a local folder, which the
# audit then reads through a file:// URL instead of calling the NVD API. The NVD has been slow for days at a
# time (in October 2026 it served about 50 KB/s and the API stalled on single pages), so every file is
# downloaded with stall detection, resumed on the next attempt, and checked before it is used.
#
# Usage: download-nvd-feeds.sh <target folder> [date of the cached database, YYYY-MM-DD]
#
# dependency-check only reads the "modified" feed (the last 8 days of changes) when its database was updated
# within the last 7 days. Otherwise it also reads every yearly feed that changed, so without a recent
# database all of them are downloaded (about 255 MB).
set -euo pipefail

target=$1
cached_on=${2:-}
base=https://nvd.nist.gov/feeds/json/cve/2.0

feeds=(modified)
age_days=
if [ -n "$cached_on" ]; then
  age_days=$(( ($(date -u +%s) - $(date -u -d "$cached_on" +%s)) / 86400 ))
fi
if [ -z "$age_days" ] || [ "$age_days" -gt 5 ]; then
  echo "No vulnerability database from the last 5 days; downloading every yearly feed."
  for ((year = 2002; year <= $(date -u +%Y); year++)); do
    feeds+=("$year")
  done
else
  echo "The cached vulnerability database is $age_days day(s) old; downloading the recent changes only."
fi

fetch() {
  local name=$1 file=$target/$1 attempt started status resume=()
  # Feeds are resumed where an attempt stopped; the small .meta files are simply fetched again.
  [[ $name == *.gz ]] && resume=(--continue-at -)
  started=$(date +%s)
  for attempt in 1 2 3 4 5 6 7 8; do
    # --speed-limit/--speed-time end a transfer that has stalled instead of waiting on it.
    status=0
    curl --fail --silent --show-error --location "${resume[@]}" \
      --connect-timeout 30 --speed-limit 1024 --speed-time 60 \
      --output "$file" "$base/$name" || status=$?
    if [[ $name == *.gz ]]; then
      # A complete feed passes the gzip check even when curl reports an error (416 on a finished resume).
      if gzip --test "$file" 2> /dev/null; then
        status=0
      elif [ "$status" -eq 0 ]; then
        # The feed was regenerated between attempts, so the resumed parts do not belong together.
        echo "$name is not a valid gzip file; downloading it again from the start"
        rm -f "$file"
        status=1
      fi
    fi
    if [ "$status" -eq 0 ]; then
      echo "Downloaded $name ($(stat -c %s "$file") bytes, $(($(date +%s) - started)) s)"
      return 0
    fi
    echo "Attempt $attempt of 8 for $name failed"
    sleep $((attempt * 15))
  done
  echo "::error::Could not download $base/$name"
  return 1
}
export -f fetch
export target base

mkdir -p "$target"
for feed in "${feeds[@]}"; do
  printf 'nvdcve-2.0-%s.meta\nnvdcve-2.0-%s.json.gz\n' "$feed" "$feed"
done | xargs -P 3 -I {} bash -c 'fetch "$1"' _ {}
