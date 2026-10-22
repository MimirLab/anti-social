#!/usr/bin/env python3
"""Open the packaged anti social APK in an isolated, visible Android emulator."""
import fcntl
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

REPO = Path(__file__).resolve().parent.parent
ROOT = Path(os.environ.get("ANTISOCIAL_BUILD_ROOT", "/run/media/red/RedtopStorage/DarwinBuild/LiberBuild"))
SDK = ROOT / "android-sdk"
ADB = SDK / "platform-tools/adb"
EMULATOR = SDK / "emulator/emulator"
APK = Path(os.environ.get("ANTISOCIAL_PREVIEW_APK", str(REPO / "artifacts/anti-social-preview.apk")))
if not APK.exists() and "ANTISOCIAL_PREVIEW_APK" not in os.environ:
    APK = REPO / "artifacts/anti-social-current-debug.apk"
SERIAL = "emulator-5560"
AVD = "AntiSocialPreview"
LOG = ROOT / "evidence/laptop-preview.log"
ENV = os.environ.copy()
ENV.update(JAVA_HOME=str(ROOT / "jdk"), ANDROID_HOME=str(SDK),
           ANDROID_USER_HOME=str(ROOT / "android-user"),
           ANDROID_AVD_HOME=str(ROOT / "android-user/avd"))
ENV["PATH"] = str(ROOT / "jdk/bin") + os.pathsep + ENV.get("PATH", "")


def adb(*args, timeout=15, check=True):
    return subprocess.run([str(ADB), "-s", SERIAL, *args], env=ENV,
                          capture_output=True, text=True, timeout=timeout, check=check)


def acceleration():
    if not EMULATOR.is_file():
        raise RuntimeError(f"Android tools are unavailable at {SDK}. Mount the build storage before opening the preview.")
    result = subprocess.run([str(EMULATOR), "-accel-check"], env=ENV,
                            capture_output=True, text=True, timeout=15)
    return result.returncode == 0, (result.stdout + result.stderr).strip()


def ensure_avd(accelerated):
    ini = ROOT / f"android-user/avd/{AVD}.ini"
    device = ROOT / f"avd/{AVD}.avd"
    if ini.exists():
        fields = dict(line.split("=", 1) for line in ini.read_text().splitlines() if "=" in line)
        device = Path(fields.get("path", str(device)))
    if not ini.exists():
        subprocess.run([str(SDK / "cmdline-tools/latest/bin/avdmanager"), "create", "avd",
                    "-n", AVD, "-k", "system-images;android-29;default;x86_64",
                    "-p", str(device), "--device", "pixel_2"],
                       input="no\n", text=True, env=ENV, check=True)
    config = device / "config.ini"
    values = dict(line.split("=", 1) for line in config.read_text().splitlines() if "=" in line)
    # Both profiles retain a 360 x 640 dp viewport. Software emulation draws
    # 36% fewer pixels, with a 30 Hz display rather than a 60 Hz one.
    values.update({"avd.ini.displayname": "anti social preview", "hw.lcd.width": "720" if accelerated else "576",
                   "hw.lcd.height": "1280" if accelerated else "1024",
                   "hw.lcd.density": "320" if accelerated else "256",
                   "hw.lcd.vsync": "60" if accelerated else "30",
                   "hw.ramSize": "3072" if accelerated else "2048",
                   "hw.cpu.ncore": "4" if accelerated else "1", "hw.keyboard": "yes",
                   "hw.camera.front": "none", "hw.camera.back": "none",
                   "showDeviceFrame": "no", "hw.gpu.enabled": "yes", "hw.gpu.mode": "host"})
    content = "\n".join(f"{k}={v}" for k, v in values.items()) + "\n"
    if config.read_text() != content:
        config.write_text(content)


def ensure_app():
    stamp = ROOT / "preview-installed.json"
    with APK.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    installed = adb("shell", "-T", "pm", "path", "app.antisocial.android", timeout=60,
                    check=False).stdout.strip()
    previous = {}
    try:
        previous = json.loads(stamp.read_text())
    except (OSError, ValueError):
        pass
    if installed.startswith("package:") and previous == {"sha256": digest, "path": installed}:
        print("Current APK is already installed; retaining its compiled code and warm app state.", flush=True)
        return
    print("Installing the current APK…", flush=True)
    print(adb("install", "--no-streaming", "-r", str(APK), timeout=900).stdout.strip(), flush=True)
    installed = adb("shell", "-T", "pm", "path", "app.antisocial.android", timeout=60).stdout.strip()
    stamp.write_text(json.dumps({"sha256": digest, "path": installed}) + "\n")


def main(args):
    started = time.monotonic()
    for path in (ADB, EMULATOR, APK):
        if not path.exists():
            raise RuntimeError(f"Missing {path}; install the toolchain/build the APK first.")
    LOG.parent.mkdir(parents=True, exist_ok=True)
    accelerated, detail = acceleration()
    running = adb("get-state", check=False).stdout.strip() == "device"
    process = None
    if running:
        if adb("emu", "avd", "name").stdout.splitlines()[0] != AVD:
            raise RuntimeError("Port 5560 belongs to another emulator; close it or choose another port.")
        print("Using the existing anti social preview window.", flush=True)
    else:
        ensure_avd(accelerated)
        print("Opening anti social’s Android preview…", flush=True)
        if not accelerated:
            print("KVM is unavailable. Using the lighter software profile. Intel VT-x must be enabled in BIOS for CPU acceleration.", flush=True)
            print(detail, flush=True)
        else:
            print("KVM hardware acceleration: four virtual CPUs, 3 GB RAM, host graphics, 60 Hz.", flush=True)
        # TCG restore fails; KVM restores but automatic saves are skipped by this
        # SDK. An old named snapshot can roll back app data, so default to a
        # normal boot in both modes until the full save/restore cycle is reliable.
        use_snapshots = os.environ.get("ANTISOCIAL_PREVIEW_SNAPSHOTS", "0") == "1"
        if use_snapshots:
            print("Experimental snapshot mode: this SDK does not reliably save on exit. Restoring an older snapshot can roll back preview data.", flush=True)
        snapshot = ["-snapshot", "antisocial-preview"] if use_snapshots else ["-no-snapshot"]
        if args.cold_boot and snapshot != ["-no-snapshot"]:
            snapshot += ["-no-snapshot-load"]
        with LOG.open("w") as log:
            process = subprocess.Popen([str(EMULATOR), "-avd", AVD, "-port", "5560",
                "-gpu", os.environ.get("ANTISOCIAL_PREVIEW_GPU", "host"), "-feature", "-Vulkan",
                "-accel", "on" if accelerated else "off", "-cores", "4" if accelerated else "1",
                "-memory", "3072" if accelerated else "2048", "-no-audio", "-no-boot-anim", *snapshot],
                env=ENV, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            if process is not None and process.poll() is not None:
                raise RuntimeError(f"Emulator exited. Details: {LOG}")
            try:
                if adb("shell", "-T", "getprop", "sys.boot_completed", timeout=5, check=False).stdout.strip() == "1":
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(2)
        else:
            raise RuntimeError(f"Android did not boot within ten minutes. Details: {LOG}")
        print("Android is ready.", flush=True)
        ensure_app()
        if args.compile:
            print("Preparing app code once; software emulation can take several minutes…", flush=True)
            print(adb("shell", "-T", "cmd", "package", "compile", "-m", "speed", "-f", "app.antisocial.android", timeout=900).stdout.strip(), flush=True)
        adb("shell", "-T", "settings", "put", "system", "screen_off_timeout", "2147483647")
        if not accelerated:
            print("Disabling emulator animations so drawing leaves CPU time for loading.", flush=True)
        for setting in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
            adb("shell", "-T", "settings", "put", "global", setting, "1" if accelerated else "0", timeout=60)
        adb("shell", "-T", "input", "keyevent", "224", timeout=60)
        print(adb("shell", "-T", "am", "start", "-W", "-n", "app.antisocial.android/.MainActivity", timeout=90).stdout.strip(), flush=True)
        print(f"anti social is open after {time.monotonic()-started:.1f} seconds. Click/tap the phone window; close that window when finished. Preview data stays local.", flush=True)
        if process is not None:
            process.wait()
    except BaseException:
        if process is not None and process.poll() is None:
            process.terminate()
        raise


if __name__ == "__main__":
    try:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--doctor", action="store_true", help="Check CPU acceleration without opening the emulator")
        parser.add_argument("--cold-boot", action="store_true", help="Skip loading a saved emulator state")
        parser.add_argument("--compile", action="store_true", help="Prepare the installed app code once")
        args = parser.parse_args()
        if args.doctor:
            available, detail = acceleration()
            print(detail)
            print("Hardware CPU acceleration:", "ready" if available else "unavailable")
            if not available:
                print("ThinkPad P52: F1 at startup → Security → Virtualization → Intel Virtualization Technology → Enabled.")
                print("After enabling it, run: bash scripts/enable_preview_kvm.sh")
            sys.exit(0 if available else 1)
        if not ROOT.is_dir():
            raise RuntimeError(f"Build storage is unavailable at {ROOT}. Mount the storage drive before opening the preview.")
        with (ROOT / "preview.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print("anti social preview is already starting or open. Use its phone window.")
                sys.exit(0)
            main(args)
    except KeyboardInterrupt:
        sys.exit(130)
    except (RuntimeError, subprocess.SubprocessError) as exc:
        print(f"Preview failed: {exc}", file=sys.stderr)
        sys.exit(1)
