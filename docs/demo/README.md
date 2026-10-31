# The README's demo

The video on the [README](../../README.md) is a real run of the app, recorded on the `rexmobile-test`
emulator against two Rex servers on this Mac, standing in for two Macs. The agents in it are scripts:
[agent.py](agent.py) draws Claude Code's own screens (its work, a question, a permission menu, a "You
should know" note and the effort it thinks at) and reports its status with the same OSC 7501 sequences
Claude Code sends, so the app shows its cards and controls for them, and one takes `/effort` as Claude
does. Each waits for a cue, a file the recording touches when the story gets to it, so things happen one
at a time. Everything else (the servers, the protocol, the app, the taps) is real.

The same run makes the README's stills, the Play listing's screenshots and its promo video, and the
video Play asks for to show what the foreground service does.

## Record it again

1. Run two separate Rex servers for the demo, Studio and MacBook, on loopback only, so none of your own
   sessions show. The emulator reaches them at `10.0.2.2`.

   ```sh
   mkdir -p build/demo/state build/demo/state2
   rex server run --listen http://127.0.0.1:47321 --allow-anonymous \
     --state-dir build/demo/state --config /dev/null
   rex server run --listen http://127.0.0.1:47322 --allow-anonymous \
     --state-dir build/demo/state2 --config /dev/null
   ```

2. Build the app with both servers as its hosts. The `benchmark` build type runs like a release
   (it isn't debuggable) and never shows the update card.

   ```sh
   ./gradlew :app:assembleBenchmark -Prex.defaultHosts=Studio=http://10.0.2.2:47321,MacBook=http://10.0.2.2:47322
   ```

3. Boot the emulator, install the APK, and give it a clean status bar: a fixed clock, full Wi-Fi and
   battery, and none of the emulator's own icons, such as its satellite one.

   ```sh
   adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
   adb shell settings put global sysui_demo_allowed 1
   demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
   demo enter
   demo clock -e hhmm 0941
   demo notifications -e visible false
   demo battery -e level 100 -e plugged false
   demo network -e satellite hide
   demo network -e wifi show -e level 4 -e fully true
   demo network -e mobile hide
   adb shell cmd uimode night yes
   ```

4. Run `docs/demo/record.sh`. It stages four Claudes, two on each server ([stage.sh](stage.sh)), clears the
   app's data so nothing from an earlier take shows, starts them and then the recording, once their cards
   have settled. One thing happens at a time: the Claude adding retries asks which queue to use and is
   answered from its card on Home; the onboarding guide is finished; the reconnect fix asks to run its
   tests, and its terminal opens to tap Yes; the tests pass and the note Claude leaves is opened and put
   away; Claude's controls open from the key bar and its effort goes from high to max; and back on the
   board the finished guide is cleared with a swipe. The raw video lands in `build/demo/demo-raw.mp4`.

5. Encode it for the README:

   ```sh
   ffmpeg -ss 0.3 -i build/demo/demo-raw.mp4 -vf "fps=60,scale=720:-2:flags=lanczos" \
     -c:v libx264 -preset slow -crf 23 -pix_fmt yuv420p -movflags +faststart -an docs/media/agent-board.mp4
   ffmpeg -ss 0.3 -i build/demo/demo-raw.mp4 -vf "fps=15,scale=360:-1:flags=lanczos,split[a][b];\
   [a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
     docs/media/agent-board.gif
   ```

## Stills

`SHOTS=<dir> docs/demo/record.sh` runs the same takes without recording, and saves a screenshot at each
moment instead: `board`, `answered`, `permission`, `note`, `note-open`, `effort` and `done`. It waits a
little longer at some of them, for the last banner to go and the note's card to settle.

- **The README's** come from a run at the emulator's own size, scaled to 540×1200: `board` is
  `docs/media/board.png`, `permission` is `answer.png` and `note-open` is `note.png`.
- **Play's** come from a run at 1080×1920, since Play takes no screenshot longer than twice its width.
  Run `adb shell wm size 1080x1920` first, and `adb shell wm size reset` after. They go in
  `docs/play/shots`: `board` as `1_board.png`, the notification (below) as `2_notification.png`,
  `permission` as `3_permission.png`, `note-open` as `4_note.png`, `effort` as `5_claude_controls.png`,
  and Settings › Look › Theme as `6_themes.png`, wearing Cretaceous. [`store-art.py`](../play/store-art.py)
  then puts each in a phone under its headline, in `fastlane/metadata/android/en-US/images/phoneScreenshots`.
- **The feature graphic's** board is calmer: `docs/demo/still.sh`, at 1080×1920 too, starts an agent in
  each state, standing still with nothing to answer, and saves `docs/play/shots/feature_board.png`.

## For Google Play

- **Notify when away.** With the app open on Settings › Alerts and Notify when away off,
  `docs/demo/away.sh` records it being turned on, Android's battery question, the app being left, Claude's
  question arriving as a notification, and its answer from there. The video lands in
  `build/demo/away-raw.mp4`, for the foreground service declaration, and the notification shade in
  `build/demo/notification.png`, for the listing.
- **The promo video**, landscape as Play shows it: the take over a backdrop with the name, made by
  [`store-art.py`](../play/store-art.py) with the icon, the feature graphic and the screenshots.

  ```sh
  python3 docs/play/store-art.py
  ffmpeg -loop 1 -i build/demo/promo-backdrop.png -ss 0.3 -i build/demo/demo-raw.mp4 \
    -filter_complex "[1:v]fps=60,scale=486:1080:flags=lanczos[p];[0:v][p]overlay=1250:0:shortest=1,format=yuv420p" \
    -c:v libx264 -preset slow -crf 20 -r 60 -movflags +faststart -an build/demo/chompy-promo-1080p.mp4
  ```

Stop the servers with Ctrl-C when done; their state is all under `build/demo/state` and `build/demo/state2`.
