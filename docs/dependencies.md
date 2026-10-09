# Dependency notices

Both Tortodrone JARs bundle Xerial SQLite JDBC **3.53.4.0**, including SQLite native
libraries. No separately installed SQLite service or library is required.

- Upstream: https://github.com/xerial/sqlite-jdbc
- Artifact: https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar
- SHA-256: `bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb`

The driver is distributed under Apache License 2.0 and BSD terms. Its bundled
license/notice files are retained in `META-INF/` in each executable JAR. SQLite
itself is public domain. Consult the bundled upstream notices for full terms.

The pinned download is verified by `scripts/BuildTools.java sqlite` and cached under
`build/deps/`. Changing the driver requires updating the version and digest,
reviewing upstream license notices, and checking both packaged applications.

## JLine terminal editing

Both JARs also bundle JLine **4.4.7** (`jdk11` artifact), tested on Java 25. It supplies interactive terminal editing and
bounded command history; its native terminal libraries are included.

- Upstream: https://github.com/jline/jline3
- Artifact: https://repo.maven.apache.org/maven2/org/jline/jline/4.4.7/jline-4.4.7-jdk11.jar
- SHA-256: `091c6aaf65de86d4106528a626fe1d9daebcd2313102d6f7e484700846d0e63d`

JLine uses the BSD 3-Clause license, included in each JAR as `licenses/jline.txt`.
`scripts/BuildTools.java jline` verifies and caches the pinned artifact. CLI automation
with redirected stdin uses plain input and does not require a native terminal.

## External town layouts

The external default map packs retain original compressed scene exports obtained from
[reference-source/SpiralKnightsSceneArchive](https://github.com/reference-source/SpiralKnightsSceneArchive).
The layout references client resources; it does not include game meshes, textures
or audio. Complete copies are checked in and shipped outside both JARs; building,
preparation and running do not depend on the archive's availability.

- Archive commit: `ae49faf06788dd83b35a959306c9fe4c25688004`
- Regular Town Square path: `Legacy/BukkitBoss/2017/1`
- Scene revision: `-1072195501`
- SHA-256: `26f15faf59dcf711e66bf0494d64fda7da6bd95ffffa5934a659a41a398d8c57`
- Bazaar: `Master/Static/2`, revision `1661137235`
- Bazaar SHA-256: `e0410e91851f3367bdd7283683ee4d45f6abf0e61d63f0e01bde5d10f5012033`
- Offline source packs: `defaults/maps/{haven,bazaar}/`
- Runtime: `build/defaults/maps/{haven,bazaar}/scene.dat`; neither JAR contains maps

The archive provides no repository license file at the pinned revision. The
layout is attributed here; no additional licensing claim is made for it.
The default collision masks are derived from their respective scenes using
read-only installed client configurations: default PC circle radius 1/3 and
collision mask 43, samples spaced 0.25 units over each scene's tile bounds.
The reusable `ScenePackExport.java` derives packs; the regular Town Square mask
was regenerated from its exact retained export. Each map
descriptor records both geometry and collision digests. Update them and compatibility
fixtures together. [Map preparation](maps.md) describes importing original archives.
