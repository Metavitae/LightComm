#!/bin/bash
# Talking in turns, run by the app itself (Oct 14): one phone sends N messages, the other reads and replies.
# The app does the turn-taking, the resends, the speed steps and the short openings; this script only
# starts both phones, waits for the end and prints what each side logged.
#   tools/talk.sh <sender: poco|8t> [messages, default 6] [start speed mode, default 5] [paces label, default 6m] [extra am-start args for the sender]
set -u
FIRST=${1:?poco|8t}; N=${2:-6}; SPEED=${3:-5}; PACES=${4:-6m}; EXTRA=${5:-}
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
TEXTS=$(for i in $(seq 1 "$N"); do printf 'PING %d|' "$i"; done); TEXTS=${TEXTS%|}
newest() { adb -s "$1" shell "ls -t $DIR/LightLab_talk_* 2>/dev/null | head -1" | tr -d '\r'; }
lines() { adb -s "$1" shell "cat '$2'" | tr -d '\r'; }
start() {  # phone extra-args -> starts talk mode, waits for "# ready", prints the file name
  local p=$1 old f; old=$(newest "$p")
  adb -s "$p" shell input keyevent KEYCODE_WAKEUP
  adb -s "$p" shell "am start -S -n $APP --es mode talk --es room lit --es paces $PACES --es expect '$TEXTS' $2" >/dev/null
  for i in $(seq 1 40); do sleep 0.5; f=$(newest "$p"); [ -n "$f" ] && [ "$f" != "$old" ] && lines "$p" "$f" | grep -q '^# ready' && { echo "$f"; return; }; done
  echo ""
}
echo "Talk: $AN sends $N messages to $BN, start speed mode $SPEED, $PACES"
FB=$(start "$B" ""); [ -n "$FB" ] || { echo "$BN never got ready"; exit 1; }
echo "  $BN listening"
FA=$(start "$A" "--es send '$TEXTS' --ei speed $SPEED $EXTRA"); [ -n "$FA" ] || { echo "$AN never got ready"; exit 1; }
echo "  $AN started"
for i in $(seq 1 $((N * 120))); do
  sleep 1; lines "$A" "$FA" | grep -q '^# DONE' && break
done
lines "$A" "$FA" | grep -E '^SEND|^# (DONE|link|auto)' | sed "s/^/  $AN: /"
lines "$B" "$FB" | grep -E '^# replied|^[0-9]+:[0-9]+:[0-9]+,' | cut -c1-160 | sed "s/^/  $BN: /"
for s in "$A" "$B"; do adb -s "$s" shell input keyevent KEYCODE_BACK; done
mkdir -p results
adb -s "$A" pull -q "$FA" results/; adb -s "$B" pull -q "$FB" results/
echo "Files pulled to ./results/"
