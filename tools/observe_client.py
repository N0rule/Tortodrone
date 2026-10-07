#!/usr/bin/env python3
"""Run the installed client and our listener in an isolated Linux network namespace.

The client installation is read-only. Scratch data and logs stay under ignored local/.
Requires bubblewrap, an X11/XWayland desktop, a built listener, and external Java runtimes.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile
from java_runtime import select_runtimes

ROOT = Path(__file__).resolve().parents[1]
MOUNT = Path("/tmp/tortodrone")


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def classpath(client):
    entries = []
    for line in (client / "getdown.txt").read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "code":
            value = value.strip()
            if value.startswith("["):
                platform, value = value.split("]", 1)
                if platform != "[linux":
                    continue
            jar = (client / value.strip()).resolve(strict=True)
            if not jar.is_relative_to(client):
                raise ValueError("classpath entry outside client installation")
            entries.append(str(jar))
    if not entries:
        raise ValueError("No classpath entries found in getdown.txt")
    return ":".join(entries)


def inside(seconds, client, knight_launcher=False, smoke=False, secure_handshake=False,
           java_home=None, client_java_home=None, local_auth=False):
    tool_java, game_java = select_runtimes(client, java_home, client_java_home)
    appdir = MOUNT / "app"
    cp = str(MOUNT / "overlay") + ":" + classpath(client)
    env = {k: v for k, v in os.environ.items() if not k.lower().startswith("steam")
           and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "LD_PRELOAD"}}
    env["HOME"] = str(MOUNT / "home")
    command = [str(game_java),
               "--add-opens=java.base/java.lang=ALL-UNNAMED",
               "--add-opens=java.base/java.util=ALL-UNNAMED",
               "--enable-native-access=ALL-UNNAMED",
               "-Dcom.threerings.getdown=false",
               "-Dcom.threerings.froth.disable_steam_api=true",
               "-Dorg.lwjgl.util.NoChecks=true", "-Dsun.java2d.d3d=false",
               "-XX:-CreateCoredumpOnCrash", "-XX:+SuppressFatalErrorMessage",
               "-Djava.library.path=" + str(client / "native"),
               "-Dappdir=" + str(appdir), "-Dresource_dir=" + str(client / "rsrc"),
               "-Duser.home=" + str(MOUNT / "home"),
               "-Dusername=tortodrone-local-probe", "-Dpassword=synthetic-unused",
               "-cp", cp, "com.threerings.projectx.client.ProjectXApp"]
    listener_command = [str(tool_java), "-cp", str(ROOT / "build/classes"),
                        "org.tortodrone.server.ConnectionLogger"]
    if secure_handshake:
        listener_command += ["--handshake-key", str(MOUNT / "keys/private.pk8")]
    if local_auth:
        listener_command.append("--local-auth")
    spec = {"client": command, "listener": listener_command, "cwd": str(appdir),
            "console": str(MOUNT / "console.log")}
    if knight_launcher:
        launcher_command = [str(tool_java), "--enable-native-access=ALL-UNNAMED",
                            "-Duser.home=" + str(MOUNT / "home"),
                            "-Dtortodrone.launchSpec=" + str(MOUNT / "commands.json")]
        if smoke:
            launcher_command += ["-Dtortodrone.smoke=true",
                                 "-Dtortodrone.screenshot=" + str(MOUNT / "launcher.png")]
        launcher_command += ["-jar", str(ROOT / "local/experiments/KnightLauncher/target/KnightLauncher.jar")]
        spec["launcher"] = launcher_command
    (MOUNT / "commands.json").write_text(json.dumps(spec, indent=2) + "\n")
    server = app = None
    received = False
    console_name = "launcher-console.log" if knight_launcher else "console.log"
    with (MOUNT / "listener.log").open("w") as listener_log, (MOUNT / console_name).open("w") as console:
        try:
            server = subprocess.Popen(listener_command, stdout=listener_log, stderr=subprocess.STDOUT, env=env)
            deadline = time.monotonic() + 5
            while "Listening on" not in (MOUNT / "listener.log").read_text():
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Listener failed to start; inspect listener.log")
                time.sleep(0.05)
            app = subprocess.Popen(launcher_command if knight_launcher else command,
                                   cwd=MOUNT / "launcher" if knight_launcher else appdir,
                                   env=env, stdout=console, stderr=subprocess.STDOUT)
            if knight_launcher and not smoke:
                print("KnightLauncher is opening. Click Play locally; close the launcher to stop the experiment.", flush=True)
                while app.poll() is None:
                    if server.poll() is not None:
                        raise RuntimeError("Listener stopped; inspect listener.log")
                    time.sleep(0.2)
                if app.returncode != 0:
                    raise RuntimeError("KnightLauncher failed; inspect launcher-console.log")
                return 0
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                marker = ("Authentication accepted by client" if local_auth else
                          "AESAuthRequest received" if secure_handshake else " RX ")
                if marker in (MOUNT / "listener.log").read_text():
                    received = True
                    break
                if app.poll() is not None:
                    break
                time.sleep(0.1)
        finally:
            stop(app)
            stop(server)
    if knight_launcher and received:
        launcher_log = (MOUNT / "launcher/knightlauncher.log").read_text()
        received = all(marker in launcher_log for marker in
                       ("TORTODRONE_LAUNCHER_READY", "TORTODRONE_CLIENT_STARTED"))
    success = ("PASS: ProjectXApp accepted local authentication; bootstrap is still pending"
               if local_auth else "PASS: ProjectXApp accepted SecureResponse and sent AESAuthRequest"
               if secure_handshake else "PASS: ProjectXApp sent TCP data to the local listener")
    print(success if received else
          "Observation failed; inspect console.log, launcher-console.log (if present), and app/projectx.log")
    return 0 if received else 1


def main(default_knight=False):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=25)
    parser.add_argument("--java-home", help="External JDK for the listener and launcher (default JAVA_HOME/PATH)")
    parser.add_argument("--client-java-home", help="External JDK capable of running the installed client")
    parser.add_argument("--local-auth", action="store_true",
                        help="Validate only the synthetic test account and send its authentication response")
    parser.add_argument("--secure-handshake", action="store_true",
                        help="Use a fresh local key and test SecureResponse -> AESAuthRequest")
    parser.add_argument("--knight-launcher", action="store_true", default=default_knight,
                        help="Open the local KnightLauncher variant; keep it open for manual testing")
    parser.add_argument("--smoke", action="store_true",
                        help="With KnightLauncher, click Play automatically and exit after receiving bytes")
    parser.add_argument("--inside", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.local_auth:
        args.secure_handshake = True
    if args.smoke and not args.knight_launcher:
        parser.error("--smoke requires --knight-launcher (or tools/knight_launcher.py)")
    os.umask(0o077)
    client = (ROOT / "local/client").resolve(strict=True)
    if not 1 <= args.seconds <= 120:
        parser.error("seconds must be between 1 and 120")
    if args.inside:
        # Internal entry point must only be used by the isolated parent invocation.
        if os.environ.get("TORTODRONE_ISOLATED") != "1":
            parser.error("--inside is reserved for the bubblewrap child")
        return inside(args.seconds, client, args.knight_launcher, args.smoke, args.secure_handshake,
                      args.java_home, args.client_java_home, args.local_auth)
    if not shutil.which("bwrap"):
        parser.error("bubblewrap (bwrap) is required; no unprotected launch fallback")
    if not os.environ.get("DISPLAY") or not Path("/tmp/.X11-unix").is_dir():
        parser.error("An X11/XWayland desktop is required")
    if not (ROOT / "build/classes/org/tortodrone/server/ConnectionLogger.class").exists():
        parser.error("Build the listener with make build first")
    if args.knight_launcher and not (ROOT / "local/experiments/KnightLauncher/target/KnightLauncher.jar").exists():
        parser.error("Build the local KnightLauncher variant first; see docs/knightlauncher.md")
    try:
        tool_java, game_java = select_runtimes(client, args.java_home, args.client_java_home)
    except (ValueError, FileNotFoundError, subprocess.SubprocessError) as error:
        parser.error(str(error))
    run = ROOT / "local/research" / ("client-run-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    if run.resolve().is_relative_to(client):
        parser.error("research directory must be outside the client installation")
    run.mkdir(parents=True)
    for name in ("app", "home"):
        (run / name).mkdir()
    overlay = ROOT / "local/extracted" / run.name
    if overlay.resolve().is_relative_to(client):
        parser.error("extracted directory must be outside the client installation")
    overlay.mkdir(parents=True)
    with zipfile.ZipFile(client / "code/config.jar") as archive:
        config = archive.read("deployment.properties").decode("iso-8859-1")
    config, count = re.subn(r"(?m)^server_host\s*=.*$", "server_host = 127.0.0.1", config)
    if count != 1 or not re.search(r"(?m)^server_ports\s*=\s*47624\s*$", config):
        raise RuntimeError("Deployment changed; re-verify endpoints before running")
    if args.secure_handshake:
        subprocess.run([str(tool_java), "-cp", str(ROOT / "build/classes"),
                        "org.tortodrone.server.HandshakeKeys", str(run / "keys")], check=True)
        public_key = (run / "keys/public.txt").read_text(encoding="ascii")
        config, count = re.subn(r"(?m)^key\.public\s*=.*$", lambda _: "key.public = " + public_key, config)
        if count != 1:
            raise RuntimeError("Deployment public key setting changed")
    (overlay / "deployment.properties").write_text(config, encoding="iso-8859-1")
    shutil.copyfile(client / "getdown.txt", run / "app/getdown.txt")
    for name in ("code", "native", "rsrc"):
        (run / "app" / name).symlink_to(client / name)
    (run / "app/full.dat").touch()
    if args.knight_launcher:
        launcher_dir = run / "launcher"
        launcher_dir.mkdir()
        shutil.copyfile(client / "getdown.txt", launcher_dir / "getdown.txt")
        for name in ("code", "native", "rsrc", "getdown-pro.jar"):
            (launcher_dir / name).symlink_to(client / name)
        (launcher_dir / "java_vm").symlink_to(game_java.parent.parent)
    command = ["bwrap", "--die-with-parent", "--ro-bind", "/", "/", "--unshare-net", "--unshare-pid",
               "--tmpfs", "/tmp", "--ro-bind", "/tmp/.X11-unix", "/tmp/.X11-unix",
               "--bind", str(run), str(MOUNT),
               "--ro-bind", str(overlay), str(MOUNT / "overlay"),
               "--proc", "/proc", "--dev-bind", "/dev", "/dev",
               "--setenv", "TORTODRONE_ISOLATED", "1", "--chdir", str(MOUNT),
               sys.executable, str(Path(__file__).resolve()), "--inside", "--seconds", str(args.seconds)]
    command += ["--java-home", str(tool_java.parent.parent),
                "--client-java-home", str(game_java.parent.parent)]
    if args.knight_launcher:
        command.append("--knight-launcher")
    if args.smoke:
        command.append("--smoke")
    if args.secure_handshake:
        command.append("--secure-handshake")
    if args.local_auth:
        command.append("--local-auth")
    print(f"Server/launcher Java: {tool_java}\nClient Java: {game_java}", flush=True)
    print(f"Logs: {run.relative_to(ROOT)}", flush=True)
    return subprocess.run(command, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
