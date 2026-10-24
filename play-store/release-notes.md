# Release notes

## Google Play — initial release (Internal testing)

Version **1.0.0 (code 55)**, `motoeye-first-upload.aab`. Paste into *Release notes → en-US* (500 characters max; this is 435).

```
<en-US>
First release on Google Play.
• Full-screen, glove-friendly remote for the MotoEye E6 HUD: D-pad, OK, BACK and volume keys sized for gloves
• Rear camera and DVR recording controls
• Live tyre pressure, temperature and sensor battery from MotoEye TPMS sensors
• One-tap key macros
• Lock-screen pad, media keys and one-tap Exit Android Auto
• Auto-reconnect with a status notification
No account, no ads, and nothing leaves your phone.
</en-US>
```

Later Play releases get their notes generated automatically from the commit subjects (the `play` job in `release.yml`).

## GitHub — v1.2.0 (code 57)

```markdown
# MotoEye Remote 1.2.0

> **Heads-up: this version installs as a new app.** The app ID changed from `com.motoeye.remote` to `com.a42r.motoeye.remote` ahead of the Google Play release, so 1.2.0 installs alongside your current copy instead of updating it. Uninstall the old "MotoEye Remote", then set the new one up again: pair the HUD, recreate your macros, and turn the accessibility services and notification access back on. The [setup guide](https://motoeye-remote.a42r.com/setup) covers each step.

## What's new

- **Google Play is on the way.** The app is in internal testing on Google Play, and future versions will reach Play and GitHub at the same time.
- **Project website**: [motoeye-remote.a42r.com](https://motoeye-remote.a42r.com/), with screenshots, a [setup guide](https://motoeye-remote.a42r.com/setup) that explains every permission, and the [privacy policy](https://motoeye-remote.a42r.com/privacy).
- **Built for Android 16** (targets API 36).

## Fixes

- **Reconnect no longer stalls when the HUD is switched off.** The first quick reconnect attempt now gives up after 8 seconds and falls back to watching for the HUD, so it reconnects as soon as the HUD is back on instead of waiting indefinitely.
- **Fewer permissions.** The app no longer asks to see every installed app. Macros can still launch any app that has a launcher icon, and Exit Android Auto still works.

## Downloads

- `motoeye-1.2.0-release.apk` — signed APK for sideloading (Android 8.0+)
```
