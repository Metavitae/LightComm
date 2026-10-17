#!/bin/bash
# Clock drift between the two test phones (Chat NOTE 2, Oct 14): every 10 min, each phone's clock vs this
# computer's clock (adb round trip measured, half of it taken as the error bar). Passive: nothing blinks.
#   tools/clock_drift.sh [file]  -> results/clock/drift.csv by default  (time, phone, phone_minus_computer_ms, round_trip_ms)
OUT=${1:-$(dirname "$0")/../results/clock/drift.csv}
[ -f "$OUT" ] || echo "computer_time,phone,phone_minus_computer_ms,round_trip_ms" > "$OUT"
while true; do
  for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
    m=$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')
    a=$(date +%s%N); p=$(adb -s "$s" shell 'echo $EPOCHREALTIME' | tr -d '\r'); b=$(date +%s%N)
    [ -z "$p" ] && continue
    awk -v a="$a" -v b="$b" -v p="$p" -v m="$m" 'BEGIN{mid=(a+b)/2/1e6; pm=p*1000; printf "%s,%s,%.1f,%.1f\n", strftime("%H:%M:%S"), m, pm-mid, (b-a)/1e6}' >> "$OUT"
  done
  sleep 600
done
