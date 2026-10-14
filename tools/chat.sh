#!/bin/bash
# A real back-and-forth (Oct 14): both phones send longer messages, taking turns, run by the app itself.
# The first phone starts; each phone sends its next message right after it answered "got it" to the other's.
#   tools/chat.sh <first: poco|8t> [start speed mode, default 5] [paces label, default 6m]
set -u
FIRST=${1:?poco|8t}; SPEED=${2:-5}; PACES=${3:-6m}
APP=com.metavitae.lightlab/.MainActivity
DIR=/sdcard/Download/LightLab
A_TEXTS='WE ARE AT THE NORTH GATE. ALL GOOD|TWO PEOPLE WITH US. ONE HURT ANKLE|CAN YOU BRING THE TRUCK TO THE ROAD?|OK. WE WAIT BY THE BIG TREE'
B_TEXTS='COPY. HOW MANY ARE YOU?|UNDERSTOOD. NEED A STRETCHER?|YES. TRUCK THERE IN 20 MIN|SEE YOU THERE. LIGHTS ON'
serial_of() { for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  [ "$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')" = "$1" ] && { echo "$s"; return; }; done; }
POCO=$(serial_of 2312FPCA6G); T8=$(serial_of KB2007)
[ -n "$POCO" ] && [ -n "$T8" ] || { echo "Both phones must be connected"; exit 1; }
for s in "$POCO" "$T8"; do
  top=$(adb -s "$s" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -o 'com\.metavitae\.[a-z]*')
  [ -n "$top" ] && [ "$top" != com.metavitae.lightlab ] && { echo "Phone $s is busy with $top. Not starting."; exit 1; }
done
if [ "$FIRST" = poco ]; then A=$POCO; B=$T8; AN=POCO; BN=8T; else A=$T8; B=$POCO; AN=8T; BN=POCO; fi
newest() { adb -s "$1" shell "ls -t $DIR/LightLab_talk_* 2>/dev/null | head -1" | tr -d '\r'; }
lines() { adb -s "$1" shell "cat '$2'" | tr -d '\r'; }
start() {  # phone extra-args -> starts talk mode in turns, waits for "# ready", prints the file name
  local p=$1 old f; old=$(newest "$p")
  adb -s "$p" shell input keyevent KEYCODE_WAKEUP
  adb -s "$p" shell "am start -S -n $APP --es mode talk --ez turns true --es room lit --es paces $PACES --ei speed $SPEED $2" >/dev/null
  for i in $(seq 1 40); do sleep 0.5; f=$(newest "$p"); [ -n "$f" ] && [ "$f" != "$old" ] && lines "$p" "$f" | grep -q '^# ready' && { echo "$f"; return; }; done
  echo ""
}
echo "Chat: $AN starts, both send 4 messages in turns, start speed mode $SPEED, $PACES"
FB=$(start "$B" "--ez first false --es send '$B_TEXTS' --es expect '$A_TEXTS'"); [ -n "$FB" ] || { echo "$BN never got ready"; exit 1; }
FA=$(start "$A" "--ez first true --es send '$A_TEXTS' --es expect '$B_TEXTS'"); [ -n "$FA" ] || { echo "$AN never got ready"; exit 1; }
T0=$(date +%s); echo "  both ready, $AN starts"
for i in $(seq 1 1200); do
  sleep 2; lines "$A" "$FA" | grep -q '^# DONE' && lines "$B" "$FB" | grep -q '^# DONE' && break
done
echo "  whole chat: $(( $(date +%s) - T0 )) s"
for p in "$A:$FA:$AN" "$B:$FB:$BN"; do IFS=: read s f n <<<"$p"
  lines "$s" "$f" | grep -E '^SEND|^# (DONE|link|auto|nothing)|^# replied|^[0-9]+:[0-9]+:[0-9]+,' | cut -c1-170 | sed "s/^/  $n: /"; done
for s in "$A" "$B"; do adb -s "$s" shell input keyevent KEYCODE_BACK; done
mkdir -p results; adb -s "$A" pull -q "$FA" results/; adb -s "$B" pull -q "$FB" results/
echo "Files pulled to ./results/"
