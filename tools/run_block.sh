#!/bin/bash
# Runs one block of the brightness-message test over adb (both phones connected).
#   tools/run_block.sh <room: dim|lit> <sender: poco|8t> [paces] [modes, default 0,1,2,3,4] [repeats, default 10]
# The receiver is the other phone. Inside each mode: the two short messages first;
# if fewer than 6 of those 20 are read right, the two long ones are skipped (noted).
# Results stay on the phones in Download/LightLab and are pulled to ./results/.
set -u
ROOM=${1:?room dim|lit}; SENDER=${2:?sender poco|8t}; PACES=${3:-hand-width}; MODES=${4:-0,1,2,3,4}; REPEATS=${5:-10}
APP=com.metavitae.lightlab/.MainActivity
DIR=/sdcard/Download/LightLab

serial_of() {  # model -> adb serial
  for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
    [ "$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')" = "$1" ] && { echo "$s"; return; }
  done
}
POCO=$(serial_of 2312FPCA6G); T8=$(serial_of KB2007)
[ -n "$POCO" ] && [ -n "$T8" ] || { echo "Both phones must be connected (POCO=$POCO 8T=$T8)"; exit 1; }
if [ "$SENDER" = poco ]; then TX=$POCO; RX=$T8; else TX=$T8; RX=$POCO; fi
# Phones are shared with other projects (The Batsight): stop if another of our apps is in front.
for s in "$TX" "$RX"; do
  top=$(adb -s "$s" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -o 'com\.metavitae\.[a-z]*')
  [ -n "$top" ] && [ "$top" != com.metavitae.lightlab ] && { echo "Phone $s is busy with $top. Not starting."; exit 1; }
done
echo "Block: room=$ROOM sender=$SENDER paces=$PACES modes=$MODES repeats=$REPEATS"

newest() { adb -s "$1" shell "ls -t $DIR/$2 2>/dev/null | head -1" | tr -d '\r'; }
lines() { adb -s "$1" shell "cat '$2'" | tr -d '\r'; }

# Receiver first (it needs ~3 s to set its sensitivity).
adb -s "$RX" shell input keyevent KEYCODE_WAKEUP
OLD_RX=$(newest "$RX" "LightLab_bright-recv_*")
adb -s "$RX" shell am start -S -n $APP --es mode bright-recv --es room "$ROOM" --es paces "$PACES" >/dev/null
LAST_TX=$(newest "$TX" "LightLab_bright-send_*")
# Handshake (idea from The Batsight): don't guess a delay; wait until the receiver says it has settled.
RXFILE=""
# The receiver writes "# ready" once its sensitivity is set and the camera has settled.
for i in $(seq 1 30); do
  sleep 1; f=$(newest "$RX" "LightLab_bright-recv_*")
  [ -n "$f" ] && [ "$f" != "$OLD_RX" ] && RXFILE=$f
  [ -n "$RXFILE" ] && lines "$RX" "$RXFILE" | grep -q '^# ready' && break
done
[ -n "$RXFILE" ] || { echo "Receiver did not start."; exit 1; }
ready=$(lines "$RX" "$RXFILE" | grep -m1 '^# ready')
[ -n "$ready" ] || { echo "Receiver never said ready. Block stopped."; exit 1; }
echo "$ready" | grep -q 'NO PICTURES' && { echo "Receiver camera gave no pictures. Block stopped."; exit 1; }
echo "Receiver file: $RXFILE ($ready)"


send() {  # modes msgs repeats -> waits until the sender has logged all sends
  local m=$1 msgs=$2 n=$3 text=${4:-}
  local want=$(( $(echo "$msgs" | tr ',' '\n' | wc -l) * n ))
  local extra=(--es msgs "$msgs"); [ -n "$text" ] && extra=(--es text "'$text'")
  adb -s "$TX" shell input keyevent KEYCODE_WAKEUP
  adb -s "$TX" shell am start -S -n $APP --es mode bright-send --es modes "$m" "${extra[@]}" --ei repeats "$n" --ei gap 3 >/dev/null
  # Handshake: wait for the sender's NEW file (a lost start is retried once instead of reading an old file).
  local f="" tries=0
  while [ "$tries" -lt 20 ]; do
    sleep 1; tries=$((tries+1)); f=$(newest "$TX" "LightLab_bright-send_*")
    [ -n "$f" ] && [ "$f" != "$LAST_TX" ] && break
    [ "$tries" -eq 10 ] && adb -s "$TX" shell am start -S -n $APP --es mode bright-send --es modes "$m" "${extra[@]}" --ei repeats "$n" --ei gap 3 >/dev/null
  done
  [ "$f" = "$LAST_TX" ] && { echo "  sender did not start (no new file)"; return; }
  LAST_TX=$f
  while :; do
    local done_n; done_n=$(lines "$TX" "$f" | grep -c '^[0-9]*,')
    echo -ne "\r  mode $m msgs $msgs: $done_n of $want sent   "
    [ "$done_n" -ge "$want" ] && break
    lines "$TX" "$f" | grep -q ',stopped,\|no flashlight' && { echo " (sender stopped)"; break; }
    sleep 15
  done
  echo; sleep 12   # let the receiver finish reading the last one
}

right_count() {  # mode id -> number of RIGHT lines for that mode in the receiver file
  lines "$RX" "$RXFILE" | awk -F, -v m="$1" '$4==m && $7=="RIGHT"' | wc -l
}

# Link check: one practice send ("LINK", 2 levels) must be read before the block counts.
# (Its length matches no test message, so it is never compared with the wrong one.)
link_ok=0
for try in 1 2; do
  before=$(lines "$RX" "$RXFILE" | grep -c ',RIGHT,')
  send 0 x 1 "LINK"
  [ "$(lines "$RX" "$RXFILE" | grep -c ',RIGHT,')" -gt "$before" ] && { link_ok=1; break; }
  echo "  link check $try not read"
done
[ "$link_ok" = 1 ] || { echo "LINK CHECK FAILED: the receiver did not read the practice send. Block stopped."; adb -s "$RX" shell input keyevent KEYCODE_BACK; exit 1; }
echo "  link check read: block starts (the practice send is not counted below)"

for m in $(echo "$MODES" | tr ',' ' '); do
  before=$(right_count "$m")
  send "$m" 0,1 "$REPEATS"
  got=$(( $(right_count "$m") - before ))
  echo "  mode $m short messages: $got of $((2*REPEATS)) right"
  if [ "$got" -lt $(( 6 * REPEATS / 10 )) ]; then
    echo "  mode $m: long messages SKIPPED (fewer than $(( 6 * REPEATS / 10 )) short ones right)"
  else
    send "$m" 2,3 "$REPEATS"
  fi
done

# Control (lesson from The Batsight: quick readouts invent answers from noise):
# 40 s with nothing sent; the receiver must report nothing at all.
before=$(lines "$RX" "$RXFILE" | grep -c '^[0-9]*:')
sleep 40
after=$(lines "$RX" "$RXFILE" | grep -c '^[0-9]*:')
[ "$after" -eq "$before" ] && echo "Control: 40 s with no light sent, nothing reported (good)" || echo "CONTROL FAILED: the receiver reported something while nothing was sent"

# Stop the receiver properly (it writes its tally), then pull both files.
adb -s "$RX" shell input keyevent KEYCODE_BACK
sleep 3
mkdir -p results
for s in "$RX" "$TX"; do
  for f in $(adb -s "$s" shell "ls -t $DIR/LightLab_bright-* | head -40" | tr -d '\r'); do adb -s "$s" pull -q "$f" results/ 2>/dev/null; done
done
echo "Pulled to ./results/. Receiver tally:"
lines "$RX" "$RXFILE" | sed -n '/STOPPED/,$p'
