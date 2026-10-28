"""Write the VT input of the unit tests' snapshot fixtures: 1000 styled lines with wide and combined
characters, some wrapped, then modes, a title and a background color a program set. With a second
path, also the same with a fullscreen program on the alternate screen, as when Claude Code's
fullscreen renderer or an editor is up."""
import sys

ESC = "\x1b"
out = [f"{ESC}]2;snapshot fixture{ESC}\\", f"{ESC}]11;rgb:10/20/30{ESC}\\"]
styles = [f"{ESC}[38;5;{{}}m", f"{ESC}[1m", f"{ESC}[4:3m", f"{ESC}[38;2;10;200;90m", ""]
extras = ["", " 漢字", " 👨‍👩‍👧", " 🇪🇸", " é", " ❤️"]
for n in range(1, 1001):
    style = styles[n % 5].replace("{}", str(n % 200))
    text = f"line {n:04d}{extras[n % 6]} " + "·" * (n % 30)
    if n % 50 == 0:
        text += " " + "wrapped " * 20
    out.append(f"{style}{text}{ESC}[0m\r\n")
out.append(f"{ESC}[?2004h{ESC}[?1000h{ESC}[?1006h{ESC}[>1u$ the prompt")
open(sys.argv[1], "wb").write("".join(out).encode())
if len(sys.argv) > 2:
    out.append(f"{ESC}[?1049h{ESC}[H{ESC}[2Ja fullscreen program")
    open(sys.argv[2], "wb").write("".join(out).encode())
