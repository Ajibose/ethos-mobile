# App Store screenshots

Screenshots uploaded by `fastlane ios app_store upload_screenshots:true`. Generating them
is not automated yet; add the images here by hand (or from a future `fastlane snapshot`
setup) and commit them.

```
screenshots/
  en-US/
    01-vaults_iphone.png        # 1320x2868 (6.9" iPhone), portrait
    02-checkin_iphone.png
    01-vaults_ipad.png          # 2064x2752 (13" iPad), portrait
    ...
  de-DE/                        # optional: one folder per App Store locale
    ...
```

- One folder per App Store locale code (`en-US`, `de-DE`, `fr-FR`, ...).
- The device type is detected from the pixel size, so file names only set the order.
- Required for review, because the app supports iPhone and iPad (`TARGETED_DEVICE_FAMILY: "1,2"`):
  - **iPhone 6.9"**: 1320x2868 or 1290x2796 (6.5", 1284x2778 or 1242x2688, is also accepted)
  - **iPad 13"**: 2064x2752 or 2048x2732
- Smaller devices are scaled from these by App Store Connect.
- PNG or JPEG, no transparency, at most 10 per device size per locale.
- `fastlane ios validate` checks folder names, sizes and counts with deliver's own validator.

Uploading **replaces** the screenshot sets of every locale that has a folder here. It only
runs when this folder changed since the previous release tag, or when `upload_screenshots`
is set on a manual run.
