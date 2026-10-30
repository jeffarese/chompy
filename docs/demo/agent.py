"""A scripted agent for the README demo: it prints like one and reports its status with OSC 7501. The Claude Code
ones draw Claude's own screens (a question, a permission menu, a "You should know" note, the effort it works at), so
the app shows its cards and controls for them and they take the keys those send.

Usage: agent.py <role> <start-file>
It waits until <start-file> exists, so every agent starts together when the recording does.

    agent.py still <start-file> <app> <state> <title> <msg> <folder>

stands still in one state instead, for the feature graphic's board (still.sh): it reports it once started, with
nothing on its screen for the app to make an answer of.
"""
import base64
import os
import signal
import sys
import textwrap
import time
import tty

ROLE = sys.argv[1]
START = sys.argv[2]


def b64(s):
    return base64.b64encode(s.encode()).decode()


def status(state, id=None, **kv):
    pairs = [f"state={state}"]
    if id:
        pairs.append(f"id={id}")
    for k, v in kv.items():
        if v is None:
            continue
        pairs.append(f"{k}={b64(v) if k in ('msg', 'title') else v}")
    sys.stdout.write("\x1b]7501;" + ":".join(pairs) + "\x1b\\")
    sys.stdout.flush()


def pwd(path):
    sys.stdout.write(f"\x1b]7;file://demo{path}\x1b\\")
    sys.stdout.flush()


def say(line, pause=0.0):
    print(line, flush=True)
    if pause:
        time.sleep(pause)


DIM, BOLD, GREEN, RED, YELLOW, CYAN, RESET = "\x1b[2m", "\x1b[1m", "\x1b[32m", "\x1b[31m", "\x1b[33m", "\x1b[36m", "\x1b[0m"


def wait_start():
    while not os.path.exists(START):
        time.sleep(0.05)


class Claude:
    """Draws a screen the way Claude Code's classic renderer does: what it has said, then whatever sits at the
    bottom (a question, a permission menu, or the input box with any "You should know" note over it). The app
    reads these screens as it reads Claude's own, so they are laid out to the terminal's width on every resize."""

    ACCENT, GREY, RULE = "\x1b[38;2;177;185;249m", "\x1b[38;2;153;153;153m", "\x1b[38;2;136;136;136m"

    def __init__(self, app, title, project):
        self.app, self.title, self.project = app, title, project
        self.said = []        # (first-line prefix, text, style)
        self.bottom = None    # a function of the width that gives the bottom's lines
        self.spinner = None
        self.effort = "high"
        self.badge = None     # shown under the footer once /effort changes the level, as Claude Code does
        self.typed = ""
        signal.signal(signal.SIGWINCH, lambda *_: self.draw())
        tty.setraw(sys.stdin.fileno())

    def size(self):
        try:
            return os.get_terminal_size(sys.stdout.fileno())
        except OSError:
            return os.terminal_size((46, 30))

    def wrap(self, prefix, text, width, style=""):
        lines = textwrap.wrap(text, max(10, width - len(prefix)), break_on_hyphens=False) or [""]
        out = []
        for i, line in enumerate(lines):
            out.append((prefix if i == 0 else " " * len(prefix)) + style + line + (RESET if style else ""))
        return out

    def say(self, prefix, text, style="", pause=0.0):
        self.said.append((prefix, text, style))
        self.draw()
        if pause:
            time.sleep(pause)

    def draw(self):
        cols, rows = self.size()
        width = cols - 1
        lines = []
        for prefix, text, style in self.said:
            if prefix == "\n":
                lines.append("")
                continue
            lines += self.wrap(prefix, text, width, style)
        if self.spinner:
            lines += ["", f"{YELLOW}✻{RESET} {self.spinner} {self.GREY}(thinking with {self.effort} effort){RESET}"]
        lines.append("")
        lines += self.bottom(cols) if self.bottom else self.input_box(cols)
        lines = lines[-rows:]
        sys.stdout.write("\x1b[?25l\x1b[H\x1b[2J" + "\r\n".join(lines))
        sys.stdout.flush()

    def rule(self, cols, ch="─"):
        return self.RULE + ch * cols + RESET

    def input_box(self, cols, note=None):
        out = []
        if note:
            out += note(cols)
            out.append("")
        out += [self.rule(cols), "❯ " + self.typed, self.rule(cols),
                f"  {self.GREY}dev │ Opus 5.5 │ {self.project}{RESET}",
                f"  {YELLOW}⏵⏵ auto mode on{RESET}{self.GREY} (shift+tab to cycle){RESET}"]
        if self.badge:
            out.append(f"  {self.ACCENT}{self.badge}{RESET} {self.effort} {self.GREY}· /effort{RESET}")
        return out

    def key(self):
        return os.read(sys.stdin.fileno(), 16).decode(errors="replace")

    def ask(self, header, question, options):
        """Claude's question tool: a numbered menu, then "Type something." and "Chat about this". Gives the label
        picked, by its number or with the arrow keys and Enter."""
        highlight = [0]
        def bottom(cols):
            out = [self.rule(cols), f" ☐ {header}", ""] + self.wrap("", question, cols - 1) + [""]
            entries = options + [("Type something.", None)]
            for i, (label, about) in enumerate(entries):
                mark = "❯" if i == highlight[0] else " "
                out.append(f"{mark} {i + 1}. {label}")
                if about:
                    out += self.wrap("     ", about, cols - 1, DIM)
            out += [self.rule(cols), f"  {len(entries) + 1}. Chat about this", "",
                    *self.wrap("", "Enter to select · ↑/↓ to navigate · Esc to cancel", cols - 1, DIM)]
            return out
        self.bottom = bottom
        self.draw()
        while True:
            k = self.key()
            if k.isdigit() and 1 <= int(k) <= len(options):
                highlight[0] = int(k) - 1
                break
            if k in ("\x1b[A", "\x1bOA"):
                highlight[0] = max(0, highlight[0] - 1)
            elif k in ("\x1b[B", "\x1bOB"):
                highlight[0] = min(len(options) - 1, highlight[0] + 1)
            elif k in ("\r", "\n"):
                break
            self.draw()
        self.bottom = None
        return options[highlight[0]][0]

    def permit(self, what, command, about):
        """Claude's permission menu for a shell command; true for a yes."""
        highlight = [0]
        choices = ["Yes", f"Yes, and don't ask again for {command} commands in this project", "No"]
        def bottom(cols):
            out = [self.rule(cols), f" {BOLD}{what}{RESET}"] + self.wrap(" ", about, cols - 1)
            out += [self.rule(cols, "╌"), f" {command}", self.rule(cols, "╌"), " Do you want to proceed?"]
            for i, c in enumerate(choices):
                out += self.wrap(f" {'❯' if i == highlight[0] else ' '} {i + 1}. ", c, cols - 1)
            return out + ["", f" {DIM}Esc to cancel · Tab to amend{RESET}"]
        self.bottom = bottom
        self.draw()
        while True:
            k = self.key()
            if k.isdigit() and 1 <= int(k) <= len(choices):
                highlight[0] = int(k) - 1
                break
            if k in ("\x1b[A", "\x1bOA"):
                highlight[0] = max(0, highlight[0] - 1)
            elif k in ("\x1b[B", "\x1bOB"):
                highlight[0] = min(len(choices) - 1, highlight[0] + 1)
            elif k in ("\r", "\n"):
                break
            self.draw()
        self.bottom = None
        return highlight[0] < 2

    def note(self, line, why, todo):
        """A "You should know" note over the input box, as the built-in plugin shows one: offered, then explained
        on 1, put away on 0 or once understood. Its buttons are typed into the empty input box."""
        explained = [False]
        def buttons(*bs):
            return "  " + "   ".join(f"{self.ACCENT}{k}{RESET}: {label}" for k, label in bs)
        def panel(cols):
            width = cols - 5
            out = [" " * (cols - 4) + f"{self.GREY}[-]{RESET}"]
            tag = "You should know" if explained[0] else "Heads up"
            body = textwrap.wrap(f"{tag} · {line}", width - 2, break_on_hyphens=False)
            out.append(f"{self.ACCENT}✦{RESET} {self.GREY}{tag} · {RESET}" + body[0][len(tag) + 3:])
            out += ["  " + b for b in body[1:]]
            if explained[0]:
                for heading, text in (("Why it happens", why), ("What to do", todo)):
                    out += ["", f"  {BOLD}{heading}{RESET}"] + ["  " + b for b in textwrap.wrap(text, width - 2)]
                out += ["", buttons(("1", "Understood"), ("2", "Chat in main session")), buttons(("0", "Dismiss"))]
            else:
                out += [buttons(("1", "Learn more"), ("2", "Knew this already")), buttons(("0", "Dismiss"))]
            return out
        self.bottom = lambda cols: self.input_box(cols, panel)
        self.draw()
        while True:
            k = self.key()
            if k == "1" and not explained[0]:
                explained[0] = True
                self.draw()
            elif k in ("0", "1", "2"):
                break
        self.bottom = None
        self.draw()

    def chat(self):
        """The empty input box, for good: "/effort <level>" sets the level and shows its badge, as Claude Code does;
        anything else is only echoed."""
        levels = {"low": "○", "medium": "◔", "high": "◑", "xhigh": "◕", "max": "●"}
        while True:
            k = self.key().replace("\x1b[200~", "").replace("\x1b[201~", "")
            for ch in k:
                if ch in "\r\n":
                    words = self.typed.split()
                    if len(words) == 2 and words[0] == "/effort" and words[1] in levels:
                        self.effort, self.badge = words[1], levels[words[1]]
                        self.said += [("❯ ", self.typed, ""), ("  ⎿  ", f"Set effort level to {self.effort}", DIM)]
                    elif self.typed:
                        self.said.append(("❯ ", self.typed, ""))
                    self.typed = ""
                elif ch == "\x7f":
                    self.typed = self.typed[:-1]
                elif ch.isprintable():
                    self.typed += ch
            self.draw()


def claude():
    app, title = "claude-code", "Fix reconnect after a network change"
    pwd("/Users/dev/src/chompy")
    status("idle", app=app, title=title)
    wait_start()
    c = Claude(app, title, "chompy")
    c.say("❯ ", "The app doesn't reconnect after switching from wifi to LTE", pause=0.5)
    status("working", app=app, title=title, msg="Reading HostConnection.kt")
    c.spinner = "Reading…"
    c.say("⏺ ", "Read(app/src/main/java/…/HostConnection.kt)", pause=3.0)
    status("working", app=app, title=title, msg="Tracing the network callback")
    c.say("⏺ ", "Search(NetworkCallback)", pause=3.0)
    status("working", app=app, title=title, msg="Editing NetworkWatch.kt")
    c.say("⏺ ", "Update(NetworkWatch.kt)")
    c.say("     ", "+ override fun onLost(network: Network) = reconnectSoon()", GREEN)
    c.say("     ", "+ override fun onAvailable(network: Network) = reconnectSoon()", GREEN, 3.5)
    c.spinner = None
    status("blocked", app=app, title=title, kind="permission", msg="Run ./gradlew testDebugUnitTest?")
    c.permit("Bash command", "./gradlew testDebugUnitTest", "Run the unit tests")
    status("working", app=app, title=title, msg="Running the tests")
    c.spinner = "Testing…"
    c.say("⏺ ", "Bash(./gradlew testDebugUnitTest)")
    for n in (31, 88, 147, 212):
        c.said = [x for x in c.said if not x[1].endswith("tests completed")]
        c.say("  ⎿  ", f"{n} tests completed", DIM, 0.45)
    c.say("  ⎿  ", "BUILD SUCCESSFUL in 41s", GREEN, 0.4)
    c.spinner = None
    status("done", app=app, title=title, msg="Reconnects on network changes; 212 tests pass")
    c.say("⏺ ", "Done. The app now reconnects when the network changes, and all 212 tests pass.")
    c.say("", "✻ Worked for 48s", DIM, 0.6)
    c.note("NetworkCallback only fires while the app runs, so a phone that switched networks in the "
           "background reconnects when it comes back, not before.",
           "Android stops delivering network callbacks to an app it has put in the background.",
           "Check the connection in onResume as well as in the callback.")
    c.chat()


def claude_asks():
    app, title = "claude-code", "Add retries to the webhook sender"
    pwd("/Users/dev/src/billing")
    status("idle", app=app, title=title)
    wait_start()
    c = Claude(app, title, "billing")
    c.say("❯ ", "Add retries to the webhook sender", pause=0.5)
    status("working", app=app, title=title, msg="Reading webhooks/sender.go")
    c.spinner = "Exploring…"
    c.say("⏺ ", "Read(webhooks/sender.go)", pause=3.0)
    status("working", app=app, title=title, msg="Planning the retry policy")
    c.say("⏺ ", "Read(queue/queue.go)", pause=3.5)
    c.say("⏺ ", "Exponential backoff, 5 attempts, with jitter. One thing to settle first.", pause=0.6)
    c.spinner = None
    status("blocked", app=app, title=title, kind="question", msg="Which queue should the retries go through?")
    picked = c.ask("Queue", "Which queue should the retries go through?", [
        ("webhooks", "A queue of their own: a slow endpoint can't hold up other jobs."),
        ("jobs", "The shared queue: one worker pool, nothing new to deploy."),
    ])
    c.say("⏺ ", "User answered Claude's questions:")
    c.say("  ⎿  ", f"· Which queue should the retries go through? → {picked}")
    status("working", app=app, title=title, msg=f"Adding a {picked} retry queue")
    c.spinner = "Writing…"
    c.say("⏺ ", f"Update(webhooks/sender.go): retries go through the {picked} queue", pause=4.0)
    c.say("⏺ ", "Write(webhooks/retry_test.go)", pause=3600)


def codex():
    app, title = "codex", "Port the CLI flags to cobra"
    pwd("/Users/dev/src/deploy-tool")
    status("idle", app=app, title=title)
    wait_start()
    steps = ["Reading cmd/root.go", "Mapping 23 flags", "Rewriting cmd/deploy.go", "Updating the help text",
             "Running go vet"]
    say(f"{BOLD}codex{RESET} Port the CLI flags to cobra\n")
    i = 0
    while True:
        status("working", app=app, title=title, msg=steps[i % len(steps)])
        say(f"• {steps[i % len(steps)]}", 4.0)
        i += 1


def terraform():
    app, title = "terraform", "Plan staging"
    pwd("/Users/dev/src/infra/staging")
    status("idle", app=app, title=title)
    wait_start()
    say(f"{BOLD}terraform plan{RESET}\n")
    for line in ("module.vpc: Refreshing state...", "module.db: Refreshing state...",
                 "module.api: Refreshing state...", "module.queue: Refreshing state...", "Computing the plan..."):
        status("working", app=app, title=title, msg=line)
        say(line, 2.0)
    status("done", app=app, title=title, msg="Plan: 3 to add, 1 to change, 0 to destroy")
    say(f"\n{BOLD}Plan:{RESET} {GREEN}3 to add{RESET}, {YELLOW}1 to change{RESET}, 0 to destroy.")
    time.sleep(3600)


def cargo():
    app, title = "cargo", "Test the parser"
    pwd("/Users/dev/src/vt-parser")
    status("idle", app=app, title=title)
    wait_start()
    status("working", app=app, title=title, msg="cargo test")
    status("working", id="build", title="Build")
    say(f"{BOLD}cargo test{RESET}")
    for p in range(10, 101, 15):
        status("working", app=app, title=title, msg="Compiling 84 crates")
        say(f"   {GREEN}Compiling{RESET} crate {p}/100", 1.0)
    status("done", id="build", title="Build")
    status("working", app=app, title=title, msg="Running 148 tests")
    status("working", id="build/test", title="Unit tests")
    say(f"     {GREEN}Running{RESET} unittests src/lib.rs", 2.5)
    status("error", app=app, title=title, msg="2 of 148 tests failed: csi::params, osc::overlong")
    status("error", id="build/test", title="Unit tests", msg="2 failed")
    say(f"test csi::params ... {RED}FAILED{RESET}")
    say(f"test osc::overlong ... {RED}FAILED{RESET}")
    say(f"\ntest result: {RED}FAILED{RESET}. 146 passed; 2 failed")
    time.sleep(3600)


def gemini():
    app, title = "gemini", "Draft the 0.4.1 release notes"
    pwd("/Users/dev/src/site")
    status("idle", app=app, title=title)
    wait_start()
    steps = ["Reading the changelog", "Summarising 14 commits", "Writing highlights", "Checking links",
             "Polishing the wording"]
    say(f"{BOLD}gemini{RESET} Draft the 0.4.1 release notes\n")
    i = 0
    while True:
        status("working", app=app, title=title, msg=steps[i % len(steps)])
        say(f"✦ {steps[i % len(steps)]}", 5.0)
        i += 1


def still():
    app, state, title, msg, folder = sys.argv[3:8]
    pwd(folder)
    status("idle", app=app, title=title)
    wait_start()
    status(state, app=app, title=title, msg=msg)
    say(f"{BOLD}{app}{RESET} {title}\n\n{msg}")
    time.sleep(3600)


{"still": still, "claude": claude, "claude-asks": claude_asks, "codex": codex, "terraform": terraform, "cargo": cargo, "gemini": gemini}[ROLE]()
