#!/usr/bin/env python3
"""Smoke-test the built distribution and example plugin against a local query fixture."""
import os
import argparse
import queue
import shutil
import socket
import subprocess
import tempfile
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, default=ROOT / "build/install/cerberus-ts")
    args = parser.parse_args()
    executable = args.distribution.resolve() / "bin/cerberus-ts"
    plugin = ROOT / "examples/welcome-plugin/build/libs/welcome-plugin.jar"
    if not executable.is_file() or not plugin.is_file():
        raise SystemExit("Run ./gradlew installDist :examples:welcome-plugin:jar first")
    with tempfile.TemporaryDirectory(prefix="cerberus-smoke-") as temporary, socket.socket() as listener:
        base = Path(temporary)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(15)
        failures = queue.Queue()

        def query():
            try:
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(15)
                    stream = connection.makefile("rwb", buffering=0)
                    stream.write(b"TS3\n\rWelcome to the TeamSpeak 3 ServerQuery interface\n\r")
                    for line in stream:
                        name = line.decode().split(" ", 1)[0].strip()
                        if name == "version":
                            stream.write(b"version=3.13.7 platform=Linux\n")
                        elif name == "whoami":
                            stream.write(b"client_id=1 virtualserver_id=1\n")
                        stream.write(b"error id=0 msg=ok\n")
            except Exception as error:
                failures.put(error)

        thread = threading.Thread(target=query, daemon=True)
        thread.start()
        plugins = base / "plugins"
        plugins.mkdir()
        shutil.copy2(plugin, plugins / "welcome.jar")
        config = base / "cerberus.properties"
        config.write_text(
            f"generation=TS3\ntransport=RAW\nhost=127.0.0.1\nport={listener.getsockname()[1]}\n"
            f"data.directory={base / 'data'}\nplugin.directory={plugins}\ncommand.interval.ms=0\nreconnect.attempts=0\nweb.enabled=false\n",
            encoding="utf-8",
        )
        environment = {key: value for key, value in os.environ.items() if not key.startswith("CERBERUS_")}
        environment["CERBERUS_PASSWORD"] = "local-fixture-only"
        process = subprocess.Popen([str(executable), "--config", str(config), "--console"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, env=environment, cwd=base)
        lines = queue.Queue()

        def read_output():
            for line in process.stdout:
                lines.put(line)
            lines.put(None)

        threading.Thread(target=read_output, daemon=True).start()
        output = []
        try:
            while True:
                line = lines.get(timeout=15)
                if line is None:
                    raise RuntimeError("Distribution exited before connecting")
                output.append(line)
                if "[welcome] CONNECTED" in line:
                    break
            process.stdin.write('hello "Kotlin Welt"\nstop\n')
            process.stdin.flush()
            process.wait(timeout=15)
            while True:
                line = lines.get(timeout=5)
                if line is None:
                    break
                output.append(line)
            if process.returncode != 0 or "Hallo Kotlin Welt!" not in "".join(output):
                raise RuntimeError("Distribution or plugin command failed")
            thread.join(timeout=3)
            if not failures.empty():
                raise failures.get()
            print("Distribution smoke test passed: startup, query login, plugin command, clean shutdown.")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


if __name__ == "__main__":
    main()
