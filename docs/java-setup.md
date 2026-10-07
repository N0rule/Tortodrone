# External Java Setup

CONFIRMED (HIGH, R3): Adoptium Eclipse Temurin **25.0.4.1+1 LTS**, Linux x86-64,
is installed under the user's directory:

```text
~/.local/share/java/jdk-25.0.4.1+1
~/.local/share/java/temurin-25 -> jdk-25.0.4.1+1
```

`~/.local/bin/java`, `javac`, `jar`, `javap`, and `jshell` link into this JDK.
That directory was already on this workstation's PATH. No system package,
Steam runtime, shell configuration, or PrismLauncher installation was replaced.
Temurin 21.0.12.1+1 is also installed at `~/.local/share/java/temurin-21`, but the
default tools point to Java 25.

## Verify and use

```sh
java -version
javac -version
make test
python3 tools/knight_launcher.py --local-auth
```

For tools that need JAVA_HOME, including the experimental launcher's Maven build:

```sh
export JAVA_HOME="$HOME/.local/share/java/temurin-25"
```

In fish:

```fish
set -gx JAVA_HOME $HOME/.local/share/java/temurin-25
```

These commands set the variable for that shell. The Java tool links work without
JAVA_HOME. The emulator still compiles with `--release 17`; external Java 25 is
used for the complete client test setup.

## Why Java 25

CONFIRMED (HIGH): the installed `ProjectXApp.class` has class-file major 69,
which requires Java 25 or newer. This is a bytecode requirement, independently
verified from `projectx-pcode.jar`, rather than inferred only from the bundled
runtime version. Java 21 can build/run the emulator but cannot load this client.

The client wrapper now selects external Java using `--java-home`, JAVA_HOME, or
PATH and normally uses that runtime for the server, launcher, and game. A
separate `--client-java-home` or `SK_CLIENT_JAVA_HOME` is available when needed.
It reads the installed main class's version and rejects an incompatible runtime.
It also refuses an executable from inside the game installation. There is no
automatic fallback to the bundled runtime or a PrismLauncher-specific path.

CONFIRMED (HIGH): KnightLauncher's real Play path reached local authentication
with all three JVMs running this external Temurin 25 installation.

## Installation provenance

The installation used the official [Adoptium API](https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse)
and [archive installation method](https://adoptium.net/installation/archives).
The archive was downloaded from the API's `adoptium/temurin25-binaries` release
URL and its SHA-256 matched the value provided in official package metadata
before extraction. It was installed without root privileges.

Archive: `OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz`.

SHA-256:

```text
dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e
```

Package metadata and downloads remain ignored under
`local/research/java-install/`. Installed binaries live outside this repository.
This is a pinned local installation; it does not automatically update.
