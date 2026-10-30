# Chompy's privacy policy

Last updated: 30 October 2026

Chompy is an Android client for Rex servers you run yourself. It has no servers, accounts, analytics,
ads or crash reporting of its own. Its developer receives nothing from the app.

## What Chompy talks to

- **The Rex servers you add.** Chompy connects only to the addresses you type in or scan from a
  pairing code. What you see and type in a terminal, and your agents' status, go between your phone
  and your server, and nowhere else.
- **Google's code scanner, if you scan a pairing code.** The scanner is part of Google Play services.
  It reads the code on the phone, and Google keeps neither the image nor what it read. Like every ML
  Kit feature, it sends Google diagnostics: the phone's model and Android version, the app's name and
  version, how long a scan took, and an identifier for the installation that isn't meant to identify
  you or the phone. Google describes this in [ML Kit's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
  You can type an address instead and never open the scanner.
- **GitHub, in the version from GitHub only.** That version checks Chompy's releases on GitHub now and
  then, and downloads an update from there when you install it. GitHub sees the request as it sees any
  visit, under [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
  The version from Google Play makes no such checks: Play updates it.

Notifications are made on the phone, from what your servers report. No push service is involved.

## What Chompy keeps on your phone

The hosts you added, your settings, which finished results you've already seen, a log of how the app
reconnected after it was away, and the last crash report, which Settings shows you and nothing sends.
They stay in the app's private storage, aren't included in Android's backups, and go when you
uninstall Chompy.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | To reach your servers, and to reconnect when the phone changes network |
| Notifications | To say when an agent needs you, finishes or fails |
| Foreground service | To keep connected to your servers while the app is away, if you turn on Notify when away |
| Run in the background (battery) | Asked for when you turn on Notify when away, so Android doesn't close that connection |
| Vibrate | For alerts while the app is open |
| Install apps (GitHub version only) | To install Chompy's own updates. The Google Play version doesn't have it |

Chompy doesn't use the camera itself: Google's scanner does, for the moment it is open.

## Children

Chompy is a tool for software developers and isn't directed at children.

## Changes

Changes to this policy are made in this file, with the date above.
[Its history](https://github.com/jeffarese/chompy/commits/main/PRIVACY.md) shows every earlier version.

## Contact

Jeff Arese, [jeffarese@gmail.com](mailto:jeffarese@gmail.com)
