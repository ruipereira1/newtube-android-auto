# Publishing a new version

Every release is built by GitHub and signed with the fork's own key, so it installs over the
previous one and keeps people's settings.

## Once: put the signing key in GitHub

The key (`newtube-auto.jks`) is kept by the maintainer, outside the repository. Never commit it.

In **Settings → Secrets and variables → Actions → New repository secret**, add:

| Name | Value |
|:--|:--|
| `AUTO_KEYSTORE_BASE64` | the key file in base64 (`base64 -w0 newtube-auto.jks`) |
| `AUTO_KEYSTORE_PASSWORD` | the key store password |
| `AUTO_KEY_ALIAS` | `newtube-auto` |
| `AUTO_KEY_PASSWORD` | the key password |

If a secret is missing, the release workflow stops with a message pointing here.

> [!IMPORTANT]
> Keep a backup of the key. If it is lost, new versions can no longer update existing installs:
> everyone would have to uninstall (losing their settings) and install again.

## Every release

1. Update the version in `smarttubetv/build.gradle` (`versionCode` must go up).
2. Push to `android-auto-passenger-video`.
3. Tag it with a name that starts with `auto-v` and push the tag:

   ```bash
   git tag auto-v1.17.1-2
   git push origin auto-v1.17.1-2
   ```

About four minutes later the
[release](https://github.com/ruipereira1/newtube-android-auto/releases) appears with the three
APKs and the computer installer.

## Building signed APKs on your own computer

Put a `keystore.properties` file in the project folder (it is in `.gitignore`):

```properties
storeFile=/path/to/newtube-auto.jks
storePassword=...
keyAlias=newtube-auto
keyPassword=...
```

Then `./gradlew :smarttubetv:assembleStmobileRelease`. The APKs are in
`smarttubetv/build/outputs/apk/stmobile/release/`. Without the file, debug builds are signed with
a local test key and get a different package name (`...arc.auto.debug`), so they install next to
the real app instead of replacing it.

## Package name

The app is `io.github.aleixrodriala.arc.auto`, so it never clashes with upstream NewTube
(`io.github.aleixrodriala.arc`). The in-app updater is turned off in this fork: it reads
upstream's releases. Updates come from this repository's releases.
