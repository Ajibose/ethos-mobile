# App Store listing metadata

Optional. While this folder only holds this README, the listing text (name, subtitle,
description, keywords, URLs) stays managed in App Store Connect, and the release pipeline
never changes it. The only per-version text it sets is "What's New", which is generated
(see `docs/ios-app-store-release.md#release-notes`).

To manage the listing in git, add one folder per App Store locale using fastlane
[deliver's file names](https://docs.fastlane.tools/actions/deliver/#available-metadata-folder-options):

```
metadata/
  en-US/
    name.txt
    subtitle.txt
    description.txt
    keywords.txt
    promotional_text.txt
    support_url.txt
    marketing_url.txt
    privacy_url.txt
  copyright.txt
  primary_category.txt
```

Tip: `bundle exec fastlane deliver download_metadata` fills this folder from the live
listing, so the first commit matches what's in App Store Connect.

- Only the files that exist are uploaded; a missing file leaves that field unchanged.
- Don't add `release_notes.txt`. It's generated per release (put overrides in `../release_notes/`).
- Each locale folder also gets its own "What's New" (the default locale's text unless
  overridden).
- Uploads only run when this folder changed since the previous release tag, or when
  `upload_metadata` is set on a manual run.
