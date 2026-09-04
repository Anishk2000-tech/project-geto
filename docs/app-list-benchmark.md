# App-list performance check

The apps screen calls `ReportDrawnWhen` after its first metadata result (or terminal load error), so
Android records a fully-drawn marker without waiting for every installed-app icon.

Use the same physical device and installed-app set for before/after measurements. Build and install
the debug variant, then perform ten cold runs:

```bash
./gradlew :app:installDebug
adb shell am force-stop com.android.geto.debug
adb logcat -c
adb shell am start -W -S -n com.android.geto.debug/com.android.geto.activity.main.MainActivity
adb logcat -d -s ActivityTaskManager:I | rg 'Fully drawn.*com.android.geto.debug'
```

Record the fully-drawn duration from each run and compare the medians. Also scroll the complete list,
change each sort mode, search by app label and package name, clear search, and verify that package
install/update/removal callbacks change only the affected rows. The acceptance target is at least a
50% lower median time to the first metadata list with no visible scrolling regression.
