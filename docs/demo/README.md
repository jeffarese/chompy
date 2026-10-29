# The README's demo

The video on the [README](../../README.md) is a real run of the app, recorded on the `rexmobile-test`
emulator against a Rex server on this Mac. The agents in it are scripts: [agent.py](agent.py) prints
like Claude Code, Codex, Terraform, Cargo and Gemini would, and reports its status with the same
OSC 7501 sequences a real agent sends. None of them reports a percentage, as none of those programs does,
so no card shows a progress bar. The two Claude Code agents draw Claude's own screens (a question, a
permission menu, a "You should know" note and the effort it thinks at), so the app shows its cards and
controls for them, and one takes `/effort` as Claude does. Everything else (the server, the protocol, the
app, the taps) is real.

## Record it again

1. Run a separate Rex server for the demo, on loopback only, so none of your own sessions show. The
   emulator reaches it at `10.0.2.2`.

   ```sh
   mkdir -p build/demo/state
   rex server run --listen http://127.0.0.1:47321 --allow-anonymous \
     --state-dir build/demo/state --config /dev/null
   ```

2. Build the app with that server as its only host. The `benchmark` build type runs like a release
   (it isn't debuggable) and never shows the update card.

   ```sh
   ./gradlew :app:assembleBenchmark -Prex.defaultHosts=Studio=http://10.0.2.2:47321
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

4. Run `docs/demo/record.sh`. It stages six sessions ([stage.sh](stage.sh)), clears the app's data so
   nothing from an earlier take shows, starts the recording and starts every agent at once. They work
   for a few seconds before the first one needs anything. It answers Claude's question from its card,
   then opens the Claude asking for permission, taps Yes, opens the note Claude leaves once the tests
   pass and puts it away. Then it opens Claude's controls from the key bar, turns its effort up from
   high to max, and goes back to the board. The raw video lands in `build/demo/demo-raw.mp4`.

5. Encode it for the README:

   ```sh
   ffmpeg -ss 1.5 -i build/demo/demo-raw.mp4 -vf "fps=60,scale=720:-2:flags=lanczos" \
     -c:v libx264 -preset slow -crf 23 -pix_fmt yuv420p -movflags +faststart -an docs/media/agent-board.mp4
   ffmpeg -ss 1.5 -i build/demo/demo-raw.mp4 -vf "fps=15,scale=360:-1:flags=lanczos,split[a][b];\
   [a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
     docs/media/agent-board.gif
   ```

Stop the server with Ctrl-C when done; its state is all under `build/demo/state`.
