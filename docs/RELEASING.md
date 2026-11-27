# Mobile releases

Increase `mobileVersionName` and `mobileVersionCode` in [app/build.gradle.kts](../app/build.gradle.kts), then commit and push the source to release. Open **Actions → Publish Aliflix Android → Run workflow** and select that ref. Enter the release notes or leave them blank to use the commit subject. The workflow derives the tag from Gradle and checks it against the latest published version before starting expensive jobs.

| Goal | Workflow inputs |
| --- | --- |
| Publish in one run | Select the ref and enter `notes` |
| Review a signed draft | Set `draft=true` and enter `notes` |
| Publish that draft | Select the same commit and enter the successful draft's numeric run ID in `reuse_run` |
| Deploy the Worker alone | Set `backend_only=true` |

The successful draft run summary shows the ID and pinned tag to use for promotion. The tag lets you promote even after the source branch moves. GitHub Actions retains the verification receipt for 30 days. If it expires, run a new draft from the pinned tag. Promotion checks the receipt, draft assets and notes, and runs the production readiness gates. Change the notes through a fresh draft run so the Android updater and GitHub show the same text.

The same entry points work with GitHub CLI:

```bash
gh workflow run mobile-release.yml --ref YOUR_REF -f draft=true -f notes='Your release notes'
gh workflow run mobile-release.yml --ref YOUR_DRAFT_TAG -f reuse_run=123456789
```

A matching tag push or `release/v*` branch push also starts publication. A workflow needs to exist on the default branch before GitHub offers manual dispatch. A failed run may leave an incomplete draft. Use **Re-run failed jobs** to continue with the completed signing and validation jobs.

## Release checks

Preflight rejects version collisions and version codes that would fail to update existing installations. Both signed APK builds run on separate runners while Android unit tests, lint, emulator tests, and any applicable Worker checks run. The APKs stay on the GitHub draft while being verified. Actions artifacts contain only the hashes and verification receipt for the release payload.

The Worker deploys when its source differs from the latest published release, or when using `backend_only`. Draft mode tests changed Worker code. Publishing or promoting the draft runs live Ask readiness after any required deployment, verifies GitHub's asset digests, and pins the tag to the tested commit before marking the release as Latest. Draft promotion uses the check scope and any explicit waivers from the original run.

## Updater and signing

GitHub Releases serves these assets under `releases/latest/download/`:

| Asset | Consumers |
| --- | --- |
| `aliflix-mobile.apk` | ARM64 phones |
| `update-mobile-arm64.json` | Current updater, with minimum SDK, ABI, checksum, and APK size |
| `aliflix-mobile-compat.apk` | Universal migration APK for older updater clients, version 3.1.175 / code 265 |
| `update-mobile.json` | Legacy updater clients. Points at the universal migration APK. |

The workflow generates both manifests from the signed APKs. Keep these paths stable for installed clients. The bridge shares the app package and certificate with production. `-Paliflix.mobileCompatBridge=true` selects its version and universal native packaging.

Production Gradle packaging accepts `ALIFLIX_KEYSTORE_FILE`, `ALIFLIX_KEYSTORE_PASSWORD`, `ALIFLIX_KEY_ALIAS`, and `ALIFLIX_KEY_PASSWORD`. CI restores the key from `ALIFLIX_KEYSTORE_BASE64`, verifies the established certificate on each APK, and checks package name, version, and minimum SDK. Native libraries and the original model are checked against the pinned release baseline.
