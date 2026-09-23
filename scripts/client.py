"""Drives a Prism Minecraft client on this Windows PC for SlashSlabs' vanilla-client tests.

    python scripts/client.py launch [--instance NAME] [--server 127.0.0.1:25580]
    python scripts/client.py snap <out.png>             game area only (client rect)
    python scripts/client.py key <key> [<key> ...]      e.g. f1, f3, esc, t, enter, shift+f3
    python scripts/client.py hold <key> <seconds>       hold a key down (w, space, shift …)
    python scripts/client.py chat "/command ..."        opens chat, types, sends
    python scripts/client.py click <x> <y> [right]      in snap pixel space (moves the cursor)
    python scripts/client.py mousehold <secs> [right]   button held at the crosshair, cursor unmoved
    python scripts/client.py stop

Nothing is sent unless the Minecraft window really holds the foreground (a synthetic key would
otherwise land in whatever app is active). Input/snap code adapted from the StreamCraft testkit.
"""
import os, subprocess, sys, time

DEFAULT_INSTANCE = "SlashSlabs-Vanilla-26.1.2"
PRISM = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "PrismLauncher", "prismlauncher.exe")
WIN_RECT = (40, 40, 1280, 760)  # x, y, width, height of the whole window

PRELUDE = r'''
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class SSK {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint c, uint t);
  [DllImport("user32.dll")] public static extern void keybd_event(byte k, byte s, uint f, UIntPtr e);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int x, int y, uint d, UIntPtr e);
  public struct RECT { public int L, T, R, B; }
  public struct POINT { public int X, Y; }
}
"@
[SSK]::SetProcessDPIAware() | Out-Null
function Get-MCWin([string]$pat) {
  $p = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" | Where-Object { $_.CommandLine -match $pat } | Select-Object -First 1
  if (-not $p) { return [IntPtr]::Zero }
  $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
  if (-not $gp -or $gp.MainWindowTitle -notlike 'Minecraft*') { return [IntPtr]::Zero }
  return $gp.MainWindowHandle
}
function Key([byte]$vk, [bool]$up, [bool]$ext) {
  $f = 0; if ($up) { $f = $f -bor 2 }; if ($ext) { $f = $f -bor 1 }
  [SSK]::keybd_event($vk, [byte][SSK]::MapVirtualKey($vk, 0), $f, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 40
}
function Focus-MC([IntPtr]$h) {
  [SSK]::ShowWindow($h, 9) | Out-Null
  Key 0x12 $false $false; [SSK]::SetForegroundWindow($h) | Out-Null; Key 0x12 $true $false
  Start-Sleep -Milliseconds 300
}
function Get-Client([IntPtr]$h) {
  $r = New-Object SSK+RECT; [SSK]::GetClientRect($h, [ref]$r) | Out-Null
  $p = New-Object SSK+POINT; [SSK]::ClientToScreen($h, [ref]$p) | Out-Null
  [pscustomobject]@{ L = $p.X; T = $p.Y; W = $r.R - $r.L; H = $r.B - $r.T }
}
'''

VK = {**{c: ord(c.upper()) for c in "abcdefghijklmnopqrstuvwxyz0123456789"},
      **{f"f{i}": 0x6F + i for i in range(1, 13)},
      "esc": 0x1B, "enter": 0x0D, "space": 0x20, "shift": 0x10, "lshift": 0xA0, "lctrl": 0xA2, "ctrl": 0x11, "alt": 0x12, "tab": 0x09,
      "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27, "slash": 0xBF, "back": 0x08}
EXT = {"up", "down", "left", "right"}


def inst_pat(inst):
    return r"[\\/]" + inst.replace(".", r"\.").replace("-", r"\-") + r"[\\/]"


def ps(body, timeout=120):
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", PRELUDE + "\n" + body],
                       capture_output=True, text=True, timeout=timeout)
    out = (r.stdout or "").strip().splitlines()
    return out[-1].strip() if out else (r.stderr or "").strip()[-300:]


def with_window(inst, lines):
    body = "\n".join([f"$h = Get-MCWin '{inst_pat(inst)}'", "if ($h -eq [IntPtr]::Zero) { 'NOWINDOW'; exit }", "Focus-MC $h",
                      "if ([SSK]::GetForegroundWindow() -ne $h) { 'NOFOCUS'; exit }"] + lines + ["'OK'"])
    return ps(body)


def key_lines(tokens):
    lines = []
    for tok in tokens:
        parts = tok.lower().split("+")
        mods, k = parts[:-1], parts[-1]
        ext = "$true" if k in EXT else "$false"
        lines += [f"Key {VK[m]} $false $false" for m in mods]
        lines += [f"Key {VK[k]} $false {ext}", "Start-Sleep -Milliseconds 60", f"Key {VK[k]} $true {ext}"]
        lines += [f"Key {VK[m]} $true $false" for m in reversed(mods)]
        lines.append("Start-Sleep -Milliseconds 150")
    return lines


def main(argv):
    inst = os.environ.get("SLABS_INSTANCE", DEFAULT_INSTANCE)
    if "--instance" in argv:
        i = argv.index("--instance"); inst = argv[i + 1]; del argv[i:i + 2]
    verb, args = argv[0], argv[1:]
    if verb == "launch":
        server = args[args.index("--server") + 1] if "--server" in args else "127.0.0.1:25580"
        subprocess.Popen([PRISM, "--launch", inst, "--server", server], creationflags=0x00000008)
        for _ in range(180):
            time.sleep(2)
            if ps(f"$h = Get-MCWin '{inst_pat(inst)}'; if ($h -ne [IntPtr]::Zero) {{ 'UP' }}") == "UP":
                x, y, w, h = WIN_RECT
                print(ps(f"$h = Get-MCWin '{inst_pat(inst)}'; [SSK]::ShowWindow($h, 9) | Out-Null; "
                         f"[SSK]::SetWindowPos($h, [IntPtr]::Zero, {x}, {y}, {w}, {h}, 0x0040) | Out-Null; 'window up'"))
                return 0
        print("client window never appeared")
        return 2
    if verb == "snap":
        out = os.path.abspath(args[0])
        print(with_window(inst, ["$c = Get-Client $h", "Add-Type -AssemblyName System.Drawing",
                                 "$bmp = New-Object System.Drawing.Bitmap $c.W, $c.H",
                                 "[System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($c.L, $c.T, 0, 0, (New-Object System.Drawing.Size $c.W, $c.H))",
                                 f"$bmp.Save('{out}', [System.Drawing.Imaging.ImageFormat]::Png)"]))
        return 0
    if verb == "key":
        print(with_window(inst, key_lines(args)))
        return 0
    if verb == "hold":
        k, secs = args[0].lower(), float(args[1])
        ext = "$true" if k in EXT else "$false"
        print(with_window(inst, [f"Key {VK[k]} $false {ext}", f"Start-Sleep -Milliseconds {int(secs * 1000)}", f"Key {VK[k]} $true {ext}"]))
        return 0
    if verb == "hold2":  # two keys together, e.g. hold2 w ctrl 3  (sprint)
        a, b, secs = args[0].lower(), args[1].lower(), float(args[2])
        print(with_window(inst, [f"Key {VK[b]} $false $false", f"Key {VK[a]} $false $false", f"Start-Sleep -Milliseconds {int(secs * 1000)}",
                                 f"Key {VK[a]} $true $false", f"Key {VK[b]} $true $false"]))
        return 0
    if verb == "chat":
        text = " ".join(args)
        esc = "".join("{" + c + "}" if c in "+^%~(){}[]" else c for c in text)
        esc = esc.replace("'", "''")
        print(with_window(inst, key_lines(["t"]) + ["Start-Sleep -Milliseconds 300", "Add-Type -AssemblyName System.Windows.Forms",
                                                     f"[System.Windows.Forms.SendKeys]::SendWait('{esc}')", "Start-Sleep -Milliseconds 200"]
                          + key_lines(["enter"])))
        return 0
    if verb == "click":
        x, y = int(args[0]), int(args[1])
        down, up = (8, 16) if len(args) > 2 and args[2] == "right" else (2, 4)
        print(with_window(inst, ["$c = Get-Client $h", f"[SSK]::SetCursorPos($c.L + {x}, $c.T + {y}) | Out-Null", "Start-Sleep -Milliseconds 150",
                                 f"[SSK]::mouse_event({down}, 0, 0, 0, [UIntPtr]::Zero)", "Start-Sleep -Milliseconds 80",
                                 f"[SSK]::mouse_event({up}, 0, 0, 0, [UIntPtr]::Zero)"]))
        return 0
    if verb == "mousehold":  # mousehold <secs> [right] -- button held, cursor NOT moved
        secs = float(args[0])
        down, up = (8, 16) if len(args) > 1 and args[1] == "right" else (2, 4)
        # Deliberately no SetCursorPos: the game grabs the mouse, so moving it would turn the
        # camera and throw away an aim set by `/tp <player> x y z <yaw> <pitch>`.
        print(with_window(inst, [f"[SSK]::mouse_event({down}, 0, 0, 0, [UIntPtr]::Zero)", f"Start-Sleep -Milliseconds {int(secs * 1000)}",
                                 f"[SSK]::mouse_event({up}, 0, 0, 0, [UIntPtr]::Zero)"]))
        return 0
    if verb == "stop":
        print(ps(f"Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' OR Name='java.exe'\" | Where-Object {{ $_.CommandLine -match '{inst_pat(inst)}' }} | "
                 "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }; 'stopped'"))
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
