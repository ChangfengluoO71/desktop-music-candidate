# Third-party notices and license review

The project-level `LICENSE` retains the MIT license and copyright text from the source repository. It applies to the reused project code as provided upstream; third-party dependencies keep their own licenses and notices.

`docs/dependency-licenses.csv` records the 119 resolved `runtimeClasspath` components observed from the current Gradle resolution. `docs/dependency-tree-runtimeClasspath.txt` records the corresponding dependency tree. `docs/dependency-licenses-buildEnvironment.csv` and `docs/dependency-tree-buildEnvironment.txt` cover the 27 build-plugin and build-tool components. License labels were read from resolved Maven POM metadata; ZXing's POM lacks the license element, so its Apache-2.0 classification is recorded from the upstream project. Plugin marker rows point to their licensed implementations. These files describe this P0 dependency snapshot, not future versions.

## License groups observed

| License | Components / use | Conditions and handling |
|---|---|---|
| MIT | MaterialKolor runtime components | Preserve copyright and license text. `LICENSES/MaterialKolor-MIT.txt` is included. |
| Apache-2.0 | Kotlin/Kotlinx, Compose Desktop, AndroidX/Jetpack JVM artifacts, Miuix, OkHttp/Okio, ZXing, SQLite JDBC, Skiko/JBR API and other resolved runtime components | Preserve license text, notices and upstream attribution; do not imply trademarks are granted. `LICENSES/Apache-2.0.txt` is included. |
| Public Domain | `org.json:json:20240303` | Upstream release declares Public Domain. See its release license. |
| LGPL-2.1-or-later | `net.jthink:jaudiotagger:3.0.1` | Included as a separate runtime dependency. A distributable release must ship its license and corresponding source or a compliant source offer, and preserve users' ability to replace the library in the combined application. The source JAR and resulting package layout must be checked for the chosen distribution. `LICENSES/LGPL-2.1.txt` is included. |
| LGPL-2.1-or-later **or** Apache-2.0 | `net.java.dev.jna:jna:5.19.1` | Upstream documents the Apache-2.0 alternative for v4+. This project chooses the Apache option and includes that license text. Preserve JNA notices in the packaged distribution. |
| GPL-2.0-with-Classpath-Exception | OpenJDK core files in the Temurin JDK 25 runtime used to build/package | This is not a Gradle `runtimeClasspath` dependency. The JDK has per-component legal notices; retain the generated runtime image's entire `legal/` directory and inspect it before redistribution. The base license and exception text are included for reference. |

Gradle 9.3.1 and its Wrapper are build-only Apache-2.0 tools and are not included in the application runtime image. The GPL-2.0 base text and Classpath Exception are included under `LICENSES/` for reference; the packaged Temurin runtime's per-module notices remain authoritative for redistribution.

The Gradle-resolved application runtime snapshot contains no dependency identified as plain GPL. That statement excludes build-host tools and the JDK runtime image, which has its own GPL plus Classpath Exception licensing. This is a source-level inventory, not a legal opinion.

## Primary references

- [Hachimi MIT license and pinned source](https://github.com/qingyueyin/Hachimi/tree/fafbc5e12ef4ce123a7e36a6a9e9d4553109ef91)
- [Miuix repository/license](https://github.com/compose-miuix-ui/miuix)
- [OkHttp repository/license](https://github.com/square/okhttp)
- [ZXing license notes](https://github.com/zxing/zxing/wiki/License-Questions)
- [JSON-java 20240303 license](https://github.com/stleary/JSON-java/blob/20240303/LICENSE)
- [JNA license choices](https://github.com/java-native-access/jna/blob/master/README.md)
- [jaudiotagger 3.0.1 Maven metadata](https://central.sonatype.com/artifact/net.jthink/jaudiotagger/3.0.1)
- [SQLite JDBC project license](https://github.com/xerial/sqlite-jdbc)
- [Eclipse Temurin project and licensing](https://projects.eclipse.org/projects/adoptium.temurin)
- [Gradle Wrapper integrity guidance](https://docs.gradle.org/current/userguide/best_practices_security.html)

## Release gate

Before publishing an installer, regenerate both dependency inventories after any dependency change, review the actual packaged JARs/native runtime (not only POM declarations), include required notices, and verify the LGPL relinking/source obligations for the final Jaudiotagger packaging. The generated `legal/` files in the jpackage runtime image must be retained. Revisit whether Jaudiotagger remains appropriate for the product's licensing goals.
