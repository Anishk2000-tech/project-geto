<div align = "center">

<img width="100" src="app/src/main/ic_launcher-playstore.png" alt="Geto" align="center">

# Geto

Apply device settings to your apps

![GitHub Release](https://img.shields.io/github/v/release/JackEblan/Geto?style=for-the-badge)
![GitHub License](https://img.shields.io/github/license/JackEblan/Geto?style=for-the-badge)
![F-Droid Version](https://img.shields.io/f-droid/v/com.android.geto?style=for-the-badge)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/JackEblan/Geto/total?style=for-the-badge)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/en/packages/com.android.geto/)

</div>

About The Project
==================

Geto applies a saved Android-settings profile before opening an app—for example, temporarily hiding
Developer Options from a banking app. Geto needs `android.permission.WRITE_SECURE_SETTINGS` to read
and update those values. Grant it from **Settings → Permission**, either with Shizuku on the device
itself or by copying the ADB command and running it from a connected computer.

Use **Launch once** for temporary changes. Geto snapshots the real original values, applies the
profile as one recoverable transaction, and keeps a Restore notification until the originals are
verified. Use **Keep profile active** when the target must also work from its original launcher icon.
In that mode a lightweight foreground service watches only the selected setting keys and repairs
drift without polling or holding a wake lock.

Settings includes an optional **Restart protection automatically** control for unexpected service
stops, reboots, and app updates. It is off by default to avoid background work unless the user opts
in. Geto reports interrupted or incomplete recovery through its protection notifications.

> [!IMPORTANT]  
> Watch the tutorial on [YouTube](https://youtu.be/CJrJyHpVVRM?si=ACrEC0hcPed53RAj)

# Screenshots

<div style="width:100%; display:flex; justify-content:space-between;">

[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width=19% alt="1">](fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width=19% alt="2">](fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width=19% alt="3">](fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width=19% alt="4">](fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" width=19% alt="5">](fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" width=19% alt="6">](fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg" width=19% alt="6">](fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" width=19% alt="6">](fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg)
</div>

# License

**Geto** is licensed under the GNU General Public License v3.0. See the [license](LICENSE) for more
information.
