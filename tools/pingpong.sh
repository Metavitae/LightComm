#!/bin/bash
# Taking turns, first check: one phone sends, the other replies "OK" as soon as it has read it.
# Measures (computer clock, polled every 0.5 s) how long each phone needs to switch between
# listening (camera on) and sending (flashlight, camera off). No new app code: uses the adb modes.
#   tools/pingpong.sh <first sender: poco|8t> [rounds, default 3] [mode, default 5 = on/off 15 per second]
set -u
FIRST=${1:?poco|8t}; ROUNDS=${2:-3}; MODE=${3:-5}
APP=com.metavitae.lightlab/.MainActivity
DIR=/sdcard/Download/LightLab
serial_of() { for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  [ "$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')" = "$1" ] && { echo "$s"; return; }; done; }
POCO=$(serial_of 2312FPCA6G); T8=$(serial_of KB2007)
[ -n "$POCO" ] && [ -n "$T8" ] || { echo "Both phones must be connected"; exit 1; }
for s in "$POCO" "$T8"; do
  top=$(adb -s "$s" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -o 'com\.metavitae\.[a-z]*')
  [ -n "$top" ] && [ "$top" != com.metavitae.lightlab ] && { echo "Phone $s is busy with $top. Not starting."; exit 1; }
done
if [ "$FIRST" = poco ]; then A=$POCO; B=$T8; AN=POCO; BN=8T; else A=$T8; B=$POCO; AN=8T; BN=POCO; fi
now() { date +%s.%N | cut -c1-14; }
newest() { adb -s "$1" shell "ls -t $DIR/$2 2>/dev/null | head -1" | tr -d '\r'; }
lines() { adb -s "$1" shell "cat '$2'" | tr -d '\r'; }
el() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }

listen() {  # phone -> waits until its receiver says ready; echoes the receiver file
  local p=$1 old f=""; old=$(newest "$p" "LightLab_bright-recv_*")
  adb -s "$p" shell input keyevent KEYCODE_WAKEUP
  adb -s "$p" shell am start -S -n $APP --es mode bright-recv --es room lit --es paces pingpong >/dev/null
  for i in $(seq 1 60); do
    sleep 0.5; f=$(newest "$p" "LightLab_bright-recv_*")
    [ -n "$f" ] && [ "$f" != "$old" ] && lines "$p" "$f" | grep -q '^# ready' && { echo "$f"; return; }
  done
  echo ""
}
send() {  # phone text -> starts one send (returns at once)
  adb -s "$1" shell input keyevent KEYCODE_WAKEUP
  adb -s "$1" shell "am start -S -n $APP --es mode bright-send --es modes $MODE --es msgs 0 --es text '$2' --ei repeats 1 --ei gap 3" >/dev/null
}
sent_done() {  # phone oldfile -> waits until a new sender file has its send line; prints the file
  local p=$1 old=$2 f=""
  for i in $(seq 1 240); do
    sleep 0.5; f=$(newest "$p" "LightLab_bright-send_*")
    [ -n "$f" ] && [ "$f" != "$old" ] && lines "$p" "$f" | grep -qE '^1,' && { echo "$f"; return; }
  done
  echo ""
}
read_count() { lines "$1" "$2" | grep -cE '^[0-9]+:[0-9]+:[0-9]+,.*,RIGHT,'; }

echo "Ping-pong: $AN sends, $BN replies GOT IT, $ROUNDS rounds, mode $MODE"
for r in $(seq 1 "$ROUNDS"); do
  echo "Round $r"
  t=$(now); RB=$(listen "$B"); [ -n "$RB" ] || { echo "  $BN receiver never ready"; exit 1; }
  echo "  $BN listening ready after $(el "$t" "$(now)") s"
  oldA=$(newest "$A" "LightLab_bright-send_*")
  tsend=$(now); send "$A" "PING $r"
  SA=$(sent_done "$A" "$oldA"); tA=$(now)
  echo "  $AN send finished $(el "$tsend" "$tA") s after start"
  # The first phone turns to listening right after its own send.
  RA=$(listen "$A"); tAr=$(now)
  echo "  $AN listening ready $(el "$tA" "$tAr") s after its send finished"
  # Wait until B has read the ping.
  got=""
  for i in $(seq 1 60); do lines "$B" "$RB" | grep -q "PING $r" && { got=1; break; }; sleep 0.5; done
  tBread=$(now)
  if [ -z "$got" ]; then echo "  $BN did NOT read the ping"; lines "$B" "$RB" | grep -E '^[0-9]+:|^# weak' | tail -2; continue; fi
  echo "  $BN read the ping $(el "$tA" "$tBread") s after $AN finished"
  # B turns around: stop listening, send OK.
  adb -s "$B" shell input keyevent KEYCODE_BACK; sleep 1
  oldB=$(newest "$B" "LightLab_bright-send_*")
  tBs=$(now); send "$B" "GOT IT $r"
  SB=$(sent_done "$B" "$oldB"); tBe=$(now)
  echo "  $BN reply took $(el "$tBs" "$tBe") s to send"
  ok=""
  for i in $(seq 1 30); do lines "$A" "$RA" | grep -q "GOT IT $r" && { ok=1; break; }; sleep 0.5; done
  tok=$(now)
  [ -n "$ok" ] && echo "  $AN READ the reply. Whole round: $(el "$tsend" "$tok") s (turn-around on $BN: $(el "$tBread" "$tBs") s)" \
               || { echo "  $AN did NOT read the reply"; lines "$A" "$RA" | grep -E '^[0-9]+:|^# weak' | tail -2; }
  adb -s "$A" shell input keyevent KEYCODE_BACK; sleep 2
  lines "$B" "$SB" | grep -E "warm-up|^1," | cut -d, -f1-12 | sed 's/^/    '"$BN"' sender: /'
done
mkdir -p results
for s in "$A" "$B"; do for f in $(adb -s "$s" shell "ls -t $DIR/LightLab_bright-* | head -$((ROUNDS*3+2))" | tr -d '\r'); do adb -s "$s" pull -q "$f" results/ 2>/dev/null; done; done
echo "Files pulled to ./results/"
