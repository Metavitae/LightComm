#!/bin/bash
# Founder places the 8T by ear, then the chat runs by itself (Oct 14).
# 60 s to walk over; the phone that stays inside blinks the aiming pattern, the carried one beeps; once the
# carried phone has seen it CLEAR (>= 0.75) for 15 s in a row, both stop (beeps stop = placed),
# 20 s to step away, then tools/chat.sh runs.
#   tools/aim_then_chat.sh [carried phone: 8t|poco, default 8t] [paces label, default outside-mesh] [walk seconds, default 60]
set -u
OUT=${1:-8t}; PACES=${2:-outside-mesh}; WALK=${3:-60}
APP=com.metavitae.lightlab/.MainActivity
if [ "$OUT" = poco ]; then T=192.168.1.75:5555; P=192.168.1.74:5555; else T=192.168.1.74:5555; P=192.168.1.75:5555; fi   # T = carried (beeps), P = stays (blinks)
echo "$(date +%T) waiting $WALK s for the founder to walk over"; sleep "$WALK"
adb -s $T shell input keyevent KEYCODE_WAKEUP; adb -s $T shell "am start -S -n $APP --es mode aim-recv --ei rate 15" >/dev/null
adb -s $P shell input keyevent KEYCODE_WAKEUP; adb -s $P shell "am start -S -n $APP --es mode aim-send --ei rate 15 --ei secs 600" >/dev/null
echo "$(date +%T) aiming started"
sleep 4; F=$(adb -s $T shell "ls -t /sdcard/Download/LightLab/LightLab_aim_* | head -1" | tr -d '\r')
placed=""
for i in $(seq 1 300); do
  sleep 2
  # last 40 scores (about 15 s): all CLEAR?
  n=$(adb -s $T shell "tail -40 '$F'" | tr -d '\r' | awk -F, 'NF==3 && $2+0>=0.75{c++} END{print c+0}')
  [ "$n" -ge 40 ] && { placed=1; break; }
done
adb -s $T shell "am force-stop com.metavitae.lightlab"; adb -s $P shell "am force-stop com.metavitae.lightlab"
[ -n "$placed" ] || { echo "$(date +%T) never steady CLEAR in 10 min: stopped"; exit 1; }
echo "$(date +%T) placed (CLEAR 15 s); 20 s to step away"; sleep 20
mkdir -p results; adb -s $T pull -q "$F" results/
tools/chat.sh poco 5 "$PACES"
