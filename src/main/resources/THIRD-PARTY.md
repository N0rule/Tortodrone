# Dependency notices

Both Tortodrone JARs bundle Xerial SQLite JDBC **3.53.4.0**, including SQLite native
libraries. No separately installed SQLite service or library is required.

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
