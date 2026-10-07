# Dependency notices

Both Tortodrone JARs bundle Xerial SQLite JDBC **3.53.4.0**, including SQLite native
libraries. No separately installed SQLite service or library is required.

- Upstream: https://github.com/xerial/sqlite-jdbc
- Artifact: https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar
- SHA-256: `bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb`

The driver is distributed under Apache License 2.0 and BSD terms. Its bundled
license/notice files are retained in `META-INF/` in each executable JAR. SQLite
itself is public domain. Consult the bundled upstream notices for full terms.

The pinned download is verified by `scripts/fetch-sqlite.sh` and cached under
`build/deps/`. Changing the driver requires updating the version and digest,
reviewing upstream license notices, and checking both packaged applications.
