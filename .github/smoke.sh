#!/bin/bash
# Instala, abre o app, tira screenshots e salva o logcat do processo.
set -x
adb install -r out/Fluwy.apk > out/install.log 2>&1
adb logcat -c
adb shell am start -n dev.fluwy.app/.MainActivity
sleep 20
adb exec-out screencap -p > out/screen1.png
adb shell input tap 540 1700
sleep 4
adb exec-out screencap -p > out/screen2.png
adb shell pidof dev.fluwy.app > out/pid.txt || echo "PROCESSO MORREU" > out/pid.txt
adb logcat -d -v time > out/logcat_full.txt
grep -E "AndroidRuntime|FATAL|dev.fluwy|Exception|Caused by" -A3 out/logcat_full.txt | head -400 > out/logcat_crash.txt
adb shell run-as dev.fluwy.app cat files/last_crash.txt > out/last_crash.txt 2>/dev/null || true
