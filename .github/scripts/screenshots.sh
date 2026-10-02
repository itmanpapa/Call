#!/usr/bin/env bash
# Installs the debug APK on a running emulator and captures screenshots
# of the main screens in light and dark mode into ./screenshots.
set -u

PKG=de.itmanpapa.callblocker.debug
OUT=screenshots
mkdir -p "$OUT"

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk

for p in READ_PHONE_STATE READ_CALL_LOG WRITE_CALL_LOG CALL_PHONE ANSWER_PHONE_CALLS READ_CONTACTS POST_NOTIFICATIONS; do
    adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done

# a few sample calls for the call log
now=$(( $(date +%s) * 1000 ))
add_call() { # number type(1 in, 2 out, 3 missed, 5 rejected) minutes_ago duration
    adb shell content insert --uri content://call_log/calls \
        --bind number:s:"$1" --bind type:i:"$2" \
        --bind date:l:$(( now - $3 * 60000 )) --bind duration:i:"$4" --bind new:i:0
}
# newest first on purpose: ids do not follow the date order (like after a restore),
# the call log must still show each call once
add_call "+4930901820" 3 5 0
add_call "+4989123456" 1 42 125
add_call "+4917612345678" 5 90 0
add_call "+4940555000" 2 300 61
add_call "+441632960001" 3 1500 0
echo "call log rows: $(adb shell content query --uri content://call_log/calls --projection number | grep -c Row)"

# sample statistics so that the statistics screen is not empty (the debug build allows
# run-as; the app keeps its files in the device-protected storage)
seed_stats() {
    local numbers=("+4930901820" "+4917612345678" "+441632960001" "+4990012345" "")
    local reasons=("LIST,bnetza" "RATING,yacb" "RULE," "BLACKLIST," "HIDDEN,")
    local data="format,callguard-call-events,1" d k n
    for d in $(seq 29 -1 0); do
        n=$(( (d * 7 + 3) % 5 ))
        for k in $(seq 1 "$n"); do
            local i=$(( (d + k) % 5 ))
            data="$data"$'\n'"c,$(( now - d * 86400000 - k * 3600000 )),${numbers[$i]},BLOCKED,${reasons[$i]}"
        done
        data="$data"$'\n'"c,$(( now - d * 86400000 - 1800000 )),+4989123456,ALLOWED,NONE,"
    done
    local dir="/data/user_de/0/$PKG/files/stats"
    echo "$data" | adb shell "run-as $PKG sh -c 'mkdir -p $dir && cat > $dir/call_events.csv'" \
        || echo "could not seed the statistics"
}
seed_stats

shot() {
    sleep 3
    adb exec-out screencap -p > "$OUT/$1.png"
    echo "captured $1"
}

tap_text() { # taps the center of the first node with the given text
    if [ "$1" != "Wait" ] && [ "$1" != "Close app" ]; then dismiss_system_dialogs; fi
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    bounds=$(grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" /tmp/ui.xml | head -1 | sed 's/.*bounds="\([^"]*\)"/\1/')
    if [ -z "$bounds" ]; then echo "node '$1' not found"; return 1; fi
    read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | tr -c '0-9' ' ')"
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

dismiss_system_dialogs() { # e.g. "Pixel Launcher isn't responding" on a slow emulator
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    if grep -q "isn't responding\|keeps stopping" /tmp/ui.xml; then
        echo "system dialog found, dismissing"
        tap_text "Wait" || tap_text "Close app"
        sleep 2
    fi
}

tap_text_scroll() { # like tap_text, scrolls down (up to 4 times) to find the node
    for _ in 1 2 3 4 5; do
        tap_text "$1" 2>/dev/null && return 0
        adb shell input swipe 540 1700 540 700 300
        sleep 1
    done
    echo "node '$1' not found after scrolling"
    return 1
}

capture_all() { # suffix
    adb shell am force-stop "$PKG"
    adb shell am start -W -n "$PKG/dummydomain.yetanothercallblocker.MainActivity"
    sleep 3
    dismiss_system_dialogs
    shot "01_first_start_$1"
    adb shell input keyevent KEYCODE_BACK  # dismiss the "no database" dialog
    shot "02_call_log_$1"
    tap_text "Lookup" && shot "03_lookup_$1"
    adb shell input keyevent KEYCODE_BACK  # hide the keyboard to bring back the navigation bar
    tap_text "Blacklist" && shot "04_blacklist_$1"
    tap_text "Settings" && shot "05_settings_$1"
    tap_text_scroll "Databases" && sleep 1 && shot "07_sources_$1" && {
        tap_text "PhoneBlock" && sleep 1 && shot "08_phoneblock_$1" && adb shell input keyevent KEYCODE_BACK
        adb shell input keyevent KEYCODE_BACK
    }
    tap_text_scroll "Blocking rules" && sleep 1 && shot "10_rules_$1" && adb shell input keyevent KEYCODE_BACK
    tap_text_scroll "Statistics" && sleep 2 && shot "12_stats_$1" && adb shell input keyevent KEYCODE_BACK
    # the setup check is the first entry of the settings
    tap_text "Settings"
    tap_text "Setup check" && sleep 2 && shot "09_setup_check_$1" && adb shell input keyevent KEYCODE_BACK
    tap_text "Call log" && sleep 2 && tap_text "+4930901820" && shot "06_info_dialog_$1"
}

incoming_call() { # suffix: simulated call from an unknown number to see the caller-ID card
    adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    adb emu gsm call 015112345678
    sleep 8
    dismiss_system_dialogs
    shot "11_incoming_call_$1"
    adb emu gsm cancel 015112345678
    sleep 3
}

adb shell cmd uimode night no
capture_all light
incoming_call light

adb shell cmd uimode night yes
capture_all dark
incoming_call dark

ls -la "$OUT"
