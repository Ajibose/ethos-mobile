fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## iOS

### ios validate

```sh
[bundle exec] fastlane ios validate
```

Validate the release configuration. Needs no credentials and uploads nothing.

Options: submit_for_review:true makes unanswered review questions an error.

### ios beta

```sh
[bundle exec] fastlane ios beta
```

Build, sign (fastlane match) and upload to TestFlight (internal testers only).

Options: dry_run:true stops before signing; only an unsigned Release build is made, on macOS.

### ios app_store

```sh
[bundle exec] fastlane ios app_store
```

Update the App Store version: release notes, and optionally metadata, screenshots and review submission.

Options: build_number:N submit_for_review:true upload_metadata:true upload_screenshots:true dry_run:true

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
