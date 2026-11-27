# Deployment

Everything runs on free tiers: GitHub Actions and Releases for the APK, GitHub Pages for the web
app, and optionally your own free Supabase project for sync. Monthly cost: €0
([research/05](research/05-sync-and-costs.md)).

## Android: CI and the APK

`.github/workflows/android.yml` runs on every push to `main` that touches `android/` or `shared/`:

1. unit tests (`:core:test`, Robolectric `:app:testDebugUnitTest`);
2. release (R8-minified) and debug APKs;
3. lint;
4. uploads both APKs and reports as workflow artifacts;
5. on `main` only, replaces the **`latest`** GitHub release with `unpaused.apk` (the release build).

Versioning: `versionCode` is the workflow run number, `versionName` is `0.1.<run>`.

### Signing

Android only installs an update over an app signed with the same key.

* **Today:** no release key is configured, so CI signs with a debug key that it keeps in the
  Actions cache (`unpaused-debug-keystore-v1`). GitHub evicts caches unused for 7 days; if that
  happens, the next APK has a new key and must be installed fresh (export a backup first).
* **Recommended:** add a release key as repository secrets. CI then signs every build with it.

```bash
keytool -genkeypair -v -keystore unpaused-release.jks -alias unpaused -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 unpaused-release.jks > keystore.b64
gh secret set UNPAUSED_KEYSTORE_BASE64 < keystore.b64
gh secret set UNPAUSED_KEYSTORE_PASSWORD     # store password
gh secret set UNPAUSED_KEY_ALIAS --body unpaused
gh secret set UNPAUSED_KEY_PASSWORD          # key password
rm keystore.b64                              # keep the .jks somewhere safe, offline
```

Switching from the debug key to a release key is itself a key change: reinstall once.

Optional, if you don't add a release key: a scheduled workflow that restores the cache every few
days keeps the debug key from being evicted. It isn't in the repository because it adds a recurring
job; to use it, save this as `.github/workflows/keep-signing-key.yml`:

```yaml
name: Keep CI signing key
on:
  schedule: [{ cron: "17 3 */3 * *" }]
  workflow_dispatch:
permissions: { contents: read }
jobs:
  touch:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/cache/restore@v6
        with: { path: ~/.android/debug.keystore, key: unpaused-debug-keystore-v1 }
```

### Building a signed release locally

Set `UNPAUSED_KEYSTORE_PATH`, `UNPAUSED_KEYSTORE_PASSWORD`, `UNPAUSED_KEY_ALIAS` and
`UNPAUSED_KEY_PASSWORD` (see `.env.example`), then `./gradlew :app:assembleRelease`. Without them
the release build is signed with your local debug key.

## Web app: GitHub Pages

`.github/workflows/web.yml` type-checks, runs the unit and Playwright tests, builds, and deploys
`web/dist` to Pages on pushes to `main`.

One-time switch (needs a repository admin; the workflow can't do it itself):
**Settings → Pages → Build and deployment → Source: GitHub Actions.** Until then the deploy job only
leaves a notice. Afterwards the app is at `https://<owner>.github.io/<repo>/`
(here: https://ratnesh-lord.github.io/music-player/). The build uses `BASE_PATH=/<repo>/`.

The web app is static: no server, no keys baked in. Supabase details are typed in by the user and
kept in the browser.

## Sync backend

See [SETUP.md](SETUP.md#optional-sync-with-supabase) and [`backend/README.md`](../backend/README.md).
Schema changes go in new files under `backend/supabase/migrations/` (never edit an applied one).
