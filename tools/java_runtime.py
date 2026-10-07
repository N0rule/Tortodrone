"""Select external Java runtimes without touching the game installation."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import struct
import zipfile


def java_major(java):
    env = {k: v for k, v in os.environ.items()
           if k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
    result = subprocess.run([str(java), "-XshowSettings:properties", "-version"],
                            capture_output=True, text=True, env=env, timeout=10, check=True)
    match = re.search(r"java.specification.version\s*=\s*(?:1\.)?(\d+)", result.stderr)
    if not match:
        raise ValueError(f"Cannot determine Java version: {java}")
    return int(match.group(1))


def resolve_java(home=None):
    value = home or os.environ.get("JAVA_HOME")
    java = Path(value).expanduser() / "bin/java" if value else Path(shutil.which("java") or "java")
    java = java.resolve(strict=True)
    if not os.access(java, os.X_OK):
        raise ValueError(f"Java executable is not executable: {java}")
    return java


def select_runtimes(client, java_home=None, client_java_home=None):
    tool_java = resolve_java(java_home)
    tool_major = java_major(tool_java)
    if tool_major < 17:
        raise ValueError("Use Java 17+ for the server (--java-home or JAVA_HOME)")
    with zipfile.ZipFile(client / "code/projectx-pcode.jar") as archive:
        header = archive.read("com/threerings/projectx/client/ProjectXApp.class")[:8]
    if header[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid ProjectXApp class header")
    required = struct.unpack(">H", header[6:8])[0] - 44
    explicit = client_java_home or os.environ.get("SK_CLIENT_JAVA_HOME")
    if explicit:
        game_java = resolve_java(explicit)
    elif tool_major >= required:
        game_java = tool_java
    else:
        raise ValueError(f"Client requires Java {required}+; selected Java {tool_major}. Set --client-java-home")
    actual = java_major(game_java)
    if actual < required:
        raise ValueError(f"Client requires Java {required}+; selected Java {actual}. Set --client-java-home")
    if game_java.is_relative_to(client) or tool_java.is_relative_to(client):
        raise ValueError("Select an external client runtime with --client-java-home")
    return tool_java, game_java
