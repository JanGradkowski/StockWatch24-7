#!/usr/bin/env bash
# Downloads the NVD JSON 2.0 data feeds that OWASP dependency-check needs into a local folder, which the
# audit then reads through a file:// URL instead of calling the NVD API. The NVD has been slow for days at a
# time (in October 2026 it served 17-75 KB/s per file and the API stalled on single pages), so every file
# is downloaded with stall detection, resumed for as long as that makes progress, waited for while the NVD
# republishes it, and checked before use.
#
# Usage: download-nvd-feeds.sh <target folder> [date of the cached database, YYYY-MM-DD]
#
# Every update starts by reading the .meta file of the "modified" feed and of every year since 2002 (the
# local folder has no cache.properties, so dependency-check builds its view of the feeds from those). They
# are about 170 bytes each and always downloaded. Of the feeds themselves, dependency-check only reads the
# "modified" feed (the last 8 days of changes) when its database was updated within the last 7 days and
# already holds every required year. Otherwise it also reads every yearly feed that changed, so without a
# recent database all of them are downloaded (about 255 MB).
set -euo pipefail

target=$1
cached_on=${2:-}
base=https://nvd.nist.gov/feeds/json/cve/2.0

# dependency-check requires a year's feed once it is January 2 everywhere (12:00 UTC). Until then it skips
# a missing one, and the NVD may not have published it yet.
newest_year=$(date -u -d '36 hours ago' +%Y)
age_days=
if [ -n "$cached_on" ]; then
  age_days=$(( ($(date -u +%s) - $(date -u -d "$cached_on" +%s)) / 86400 ))
fi
if [ -z "$age_days" ] || [ "$age_days" -gt 5 ]; then
  echo "No vulnerability database from the last 5 days; downloading every yearly feed."
  all_years=true
elif [[ $cached_on < $newest_year-01-03 ]]; then
  # A database saved before the newest year was required does not hold it yet, which makes
  # dependency-check reload every year.
  echo "The cached vulnerability database predates the $newest_year feed; downloading every yearly feed."
  all_years=true
else
  echo "The cached vulnerability database is $age_days day(s) old; downloading the recent changes only."
  all_years=false
fi

files=(nvdcve-2.0-modified.meta nvdcve-2.0-modified.json.gz)
# Newest first: recent years are the largest feeds, so they start before the small ones.
for ((year = newest_year; year >= 2002; year--)); do
  files+=("nvdcve-2.0-$year.meta")
  if [ "$all_years" = true ]; then
    files+=("nvdcve-2.0-$year.json.gz")
  fi
done

size() {
  stat -c %s "$1" 2> /dev/null || echo 0
}

fetch() {
  local name=$1 file=$target/$1 started status code before stalled=0 missing_since= resume=()
  # Feeds are resumed where an attempt stopped; the small .meta files are simply fetched again.
  [[ $name == *.gz ]] && resume=(--continue-at -)
  started=$(date +%s)
  # Attempts go on while they make progress (a 38 MB feed can take a dozen); five in a row that add
  # nothing end the download. The workflow step's time limit bounds the whole run.
  while [ "$stalled" -lt 5 ]; do
    before=$(size "$file")
    # --speed-limit/--speed-time end a transfer that has stalled instead of waiting on it.
    status=0
    code=$(curl --fail --silent --show-error --location "${resume[@]}" \
      --connect-timeout 30 --speed-limit 1024 --speed-time 60 \
      --write-out '%{http_code}' --output "$file" "$base/$name") || status=$?
    # When the NVD republishes a feed, one of its files can be missing for a while: on 2026-10-27 the
    # new modified .meta and .json.zip were up at 10:15Z, but the .json.gz returned 404 until 10:40Z.
    # A missing file is waited for (up to 45 minutes) instead of counting as an attempt without progress.
    if [ "$code" = 404 ]; then
      missing_since=${missing_since:-$(date +%s)}
      if [ $(($(date +%s) - missing_since)) -ge 2700 ]; then
        echo "::error::$base/$name has been missing (HTTP 404) for 45 minutes"
        return 1
      fi
      echo "$name is missing on the NVD server (HTTP 404), probably while it is republished; trying again in a minute"
      # Bytes already downloaded belong to the previous version of the file.
      rm -f "$file"
      sleep 60
      continue
    fi
    missing_since=
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
      echo "Downloaded $name ($(size "$file") bytes, $(($(date +%s) - started)) s)"
      return 0
    fi
    if [[ $name == *.gz ]] && [ "$(size "$file")" -gt "$before" ]; then
      stalled=0
    else
      stalled=$((stalled + 1))
    fi
    echo "Download of $name stopped at $(size "$file") bytes; trying again"
    sleep $((10 + stalled * 20))
  done
  echo "::error::Could not download $base/$name: five attempts in a row made no progress"
  return 1
}
export -f size fetch
export target base

mkdir -p "$target"
printf '%s\n' "${files[@]}" | xargs -P 6 -I {} bash -c 'fetch "$1"' _ {}
