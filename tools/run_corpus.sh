#!/usr/bin/env bash
# Runs the corpus batch on a device and prints the per-script report.
# Usage: tools/run_corpus.sh <serial> <save.json> [region] [--failures]
set -e
export MSYS_NO_PATHCONV=1
SERIAL=$1; SAVE=$2; REGION=${3:-0,0.025,0.6,0.16}; EXTRA=$4
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -S -n com.macle.royaleassist/.debug.DebugActivity --ez corpus true --es region "$REGION" >/dev/null
sleep 15
until adb -s "$SERIAL" logcat -d -s RoyaleAssist | grep -q "CORPUS SUMMARY"; do sleep 10; done
adb -s "$SERIAL" logcat -d -s RoyaleAssist | grep "CORPUS SUMMARY" | sed 's/.*RoyaleAssist: //'
python -I "$(dirname "$0")/corpus_report.py" --serial "$SERIAL" --save "$SAVE" $EXTRA
