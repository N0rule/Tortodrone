# Dependency notices

Both Tortodrone JARs bundle Xerial SQLite JDBC **3.53.4.0**, including SQLite native
libraries. No separately installed SQLite service or library is required.

The optional desktop UI bundles FlatLaf **3.7.2** (Apache License 2.0) and
JFreeChart **1.5.6** (GNU Lesser General Public License 2.1 or later). FlatLaf supplies the
dark/light Swing look and feel; JFreeChart supplies the in-memory Performance
tab graphs. They are present in the executable JARs but are loaded only when a
desktop window is opened. `--nogui` does not initialize Swing or chart classes.
Both dependency licenses are retained in the executable JARs; JFreeChart's
license text is also checked in at [licenses/JFreeChart-LGPL-2.1-or-later.txt](../licenses/JFreeChart-LGPL-2.1-or-later.txt).

- Upstream: https://github.com/xerial/sqlite-jdbc
- Artifact: https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar
- SHA-256: `bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb`

The driver is distributed under Apache License 2.0 and BSD terms. Its bundled
license/notice files are retained in `META-INF/` in each executable JAR. SQLite
itself is public domain. Consult the bundled upstream notices for full terms.

The pinned Gradle dependency is verified by `gradle/verification-metadata.xml` and cached in
Gradle user home. Changing the driver requires updating the version and digest,
reviewing upstream license notices, and checking both packaged applications.

## JLine terminal editing

Both JARs also bundle JLine **4.4.7** (`jdk11` artifact), tested on Java 25. It supplies interactive terminal editing and
bounded command history; its native terminal libraries are included.

- Upstream: https://github.com/jline/jline3
- Artifact: https://repo.maven.apache.org/maven2/org/jline/jline/4.4.7/jline-4.4.7-jdk11.jar
- SHA-256: `091c6aaf65de86d4106528a626fe1d9daebcd2313102d6f7e484700846d0e63d`

JLine uses the BSD 3-Clause license, included in each JAR as `licenses/jline.txt`.
Gradle verifies the pinned artifact using `gradle/verification-metadata.xml`. CLI automation
with redirected stdin uses plain input and does not require a native terminal.

## External town layouts

The seven checked-in scene packs under `content/packs/vanilla/maps/` were rebuilt from local, SHA-256-verified
official gameplay captures dated 2026-10-12. The capture hashes, sizes and versions are recorded
in `content/packs/vanilla/maps/scene-source.properties`; each pack also pins its scene and collision data.
Laboratory is scene 30893 and Hall of Heroes is the four-star variant, scene 318. The maps are
shipped outside both JARs, so ordinary builds and runtime do not need the capture files or a
remote archive. The map converter uses the unchanged installed client's configurations read-only.

Scene layouts are proprietary game references. This notice records provenance and makes no
licensing claim. The capture directory included additional decoded/hash-verified scenes that are
not integrated; private guild scene 528 is excluded. See [map preparation](maps.md) for the
offline verification and candidate export workflow.
