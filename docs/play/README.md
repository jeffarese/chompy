# Chompy on Google Play

Chompy ships two ways: on Google Play, and as an APK on [GitHub's releases](https://github.com/jeffarese/chompy/releases).
This page is how a version gets to Play, and what Play Console's forms are answered with.

## One app, two builds

| | `release` | `play` |
|---|---|---|
| Built with | `./gradlew :app:assembleRelease` | `./gradlew :app:bundlePlay` |
| What comes out | `app/build/outputs/apk/release/app-release.apk` | `app/build/outputs/bundle/play/app-play.aab` |
| Signed with | The release key | The upload key: Play signs what it serves with the release key |
| Updates | From GitHub, by the app's own updater | From Play. No updater, and no permission to install apps |
| Published by | `scripts/publish-release.sh` | Uploading the bundle to Play Console |

Both are `com.jeffarese.chompy`, with the same version, and what reaches a phone from either is signed with
the same key. So an install from GitHub takes an update from Play, and the other way round. Play
doesn't let an app on it update itself, which is why `play` leaves the updater out
([`src/play/AndroidManifest.xml`](../../app/src/play/AndroidManifest.xml) drops `REQUEST_INSTALL_PACKAGES`).
Neither build takes `rex.defaultHosts` from `local.properties`: both are published. A release build
named hosts with `-P` is versioned `<version>-hosts`, and `publish-release.sh` refuses it.

The keys and their passwords live in `keystore.properties`, which isn't committed:

```properties
storeFile=/path/to/chompy-release.jks
storePassword=...
keyAlias=chompy
keyPassword=...
uploadStoreFile=/path/to/chompy-upload.jks
uploadStorePassword=...
uploadKeyAlias=upload
uploadKeyPassword=...
```

The release key's certificate has the SHA-256 fingerprint
`64:03:CD:23:09:07:AF:16:1F:18:22:B7:1B:DA:99:31:64:A5:C2:CB:84:7C:08:C1:84:F0:36:C3:89:2A:59:4A`.
A lost release key can sign no further update for GitHub installs, so keep a copy of both keys and the
file somewhere safe.

## Once, when the app is created

1. **App signing.** Under *Test and release › App integrity › Play app signing*, choose to use your own
   key, *Export and upload a key from Java keystore*. Download Play's `pepk.jar` and encryption key
   there, and run:

   ```sh
   java -jar pepk.jar --keystore=chompy-release.jks --alias=chompy --output=chompy-release-key.zip \
     --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   keytool -export -rfc -keystore chompy-upload.jks -alias upload -file upload_certificate.pem
   ```

   Upload the zip as the app signing key and `upload_certificate.pem` as the upload key. Check the app
   signing key's SHA-256 that Play then shows is the one above.
2. **Outside Play.** Android's developer verification asks that apps installed from anywhere be
   registered to a verified developer: from 30 September 2026 in Brazil, Indonesia, Singapore and
   Thailand, and everywhere from 2027. Play Console registers apps distributed outside Play too, by
   package name and key. Register `com.jeffarese.chompy` with the release key there, so the APKs from
   GitHub keep installing.
3. **Testing first.** A personal developer account created after 13 November 2023 can only publish to
   production after a closed test of the app with at least 12 testers, each opted in for the last 14
   days without a break. Create a closed testing track, upload the bundle, share its opt-in link, and
   apply for production access from the dashboard once the 14 days are up. Closed test releases are
   reviewed too, so App access below is tried early. An organisation account doesn't need the test.

## Store listing

The listing is in [`fastlane/metadata/android/en-US`](../../fastlane/metadata/android/en-US), laid out
as [fastlane supply](https://docs.fastlane.tools/actions/supply/) reads it, to paste in by hand or upload
with `fastlane supply`:

| Field | Where |
|---|---|
| App name (30) | `title.txt` |
| Short description (80) | `short_description.txt` |
| Full description (4,000) | `full_description.txt` |
| App icon, 512×512 | `images/icon.png` |
| Feature graphic, 1024×500 | `images/featureGraphic.png` |
| Phone screenshots, 1080×1920 | `images/phoneScreenshots/` |
| What's new (500) | `changelogs/<versionCode>.txt`, or `default.txt` |
| Video | A YouTube link to the promo video ([how it's made](../demo/README.md#for-google-play)) |

[`store-art.py`](store-art.py) makes the icon and the feature graphic from the art in `art/`. Elsewhere
in the listing:

- **Category:** Tools. **Tags:** developer tools, productivity.
- **Contact email:** jeffarese@gmail.com. **Website:** https://github.com/jeffarese/chompy
- **Privacy policy:** https://github.com/jeffarese/chompy/blob/main/PRIVACY.md

## App content

What each of *Policy and programs › App content* takes.

**Privacy policy.** https://github.com/jeffarese/chompy/blob/main/PRIVACY.md

**Ads.** No, the app has no ads.

**App access.** *All or some functionality in my app is restricted*, with these instructions:

> Chompy is a client for Rex, a terminal multiplexer that runs on the user's own Mac. The app has no
> accounts and no server of its own: after the welcome, the user adds the address of their own Rex server,
> or scans the code that `rex server tailscale pair` shows. Without one, the app shows its welcome and
> the screen to add a host. This video shows it connected to a Rex server: the board of agents, a
> terminal, and answers to an agent's prompts from the board and from a notification: <promo video link>

**Content rating.** The questionnaire's *All other app types* category, and No to every question: no
violence, sexual content, language, drugs, gambling or crude humour; users don't interact or share content
with other users (Chompy connects only to the user's own servers); no location is shared; nothing is for
sale; it isn't a web browser. Expect Everyone and PEGI 3.

**Target audience and content.** 18 and over. The listing isn't meant to appeal to children.

**News apps.** No. **Health apps, financial features, government apps.** None of them.

**Advertising ID.** No: the `play` build doesn't declare `com.google.android.gms.permission.AD_ID`
and nothing in it reads the ID.

**Data safety.** The only data that leaves the phone for anyone but the user's own servers is ML Kit's,
from Google's code scanner, which pairing uses
([Google's disclosure](https://developers.google.com/ml-kit/android-data-disclosure)):

- Does the app collect or share any of the required user data types? **Yes.**
- Is all of the user data collected encrypted in transit? **Yes** (ML Kit sends it over HTTPS).
- Do you provide a way for users to request that their data is deleted? **No.** The developer holds none,
  and the app's own data goes with an uninstall.
- Data types, each **collected**, **not shared**, not processed ephemerally, **optional** (the scanner
  runs only if the user scans a code instead of typing an address), for **Analytics**:
  - *App info and performance › Diagnostics*: the device's model and Android version, the app's
    package and version, the scan's latency, error codes.
  - *Device or other IDs*: a per-installation identifier.

What a terminal shows, what the user types and their agents' status go only between the phone and the
user's own server, chosen by them, and never to the developer or a third party, so they are not
declared as collected.

**Foreground service permissions.** One service, of type *special use*, with this description:

> Notify when away, which is off until the user turns it on in the welcome or in Settings › Alerts. While
> it is on, the service keeps a connection open to each of the user's own Rex servers, so that when a
> coding agent there needs the user (it asks for permission or asks a question), finishes or fails, the
> phone says so at once, and the user can answer from the notification. The servers are the user's own,
> so there is no push service that could wake the app instead. If the service were stopped or deferred,
> the user wouldn't learn that an agent is blocked waiting for them, and the agent would stay stuck. The
> service shows an ongoing notification with what it is watching, and a Stop action.

The video is `build/demo/away-raw.mp4`, from [`away.sh`](../demo/away.sh), uploaded as an unlisted
YouTube video.

**Battery optimisation.** There is no form for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, but review can
ask. The app asks for it only with Notify when away on, for the reason above: its servers can't send
push messages, so the connection is the only way to hear from them.

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:assembleRelease :app:bundlePlay`
3. `scripts/publish-release.sh notes.md` puts the APK on GitHub, where installs from there find it.
4. Upload `app-play.aab` to the track in Play Console, with the same notes, cut to 500 characters, as
   What's new. Play reviews it, which can take from hours to days, so GitHub gets each version first.

Play raises the API level apps must target each August: since 31 August 2026 it is 36, which the app
targets. Native code must load on phones with 16 KB memory pages: `libandroidx.graphics.path.so` and
`librexterm.so` both align their segments to 16 KB, as `llvm-readelf -lW` shows (`0x4000`).
