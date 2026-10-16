# Third-party notices and license review

The project-level `LICENSE` retains the MIT license and copyright text from the source repository. It applies to the reused project code as provided upstream; third-party dependencies keep their own licenses and notices.

`docs/dependency-licenses.csv` records the 122 resolved `runtimeClasspath` components observed from the current Gradle resolution, including OpenJFX 25.0.2. `docs/dependency-tree-runtimeClasspath.txt` records the corresponding dependency tree. `docs/dependency-licenses-buildEnvironment.csv` and `docs/dependency-tree-buildEnvironment.txt` cover the 27 build-plugin and build-tool components. License labels were read from resolved Maven POM metadata; ZXing's POM lacks the license element, so its Apache-2.0 classification is recorded from the upstream project. Plugin marker rows point to their licensed implementations. These files describe this P1 dependency snapshot, not future versions.

## License groups observed

| License | Components / use | Conditions and handling |
|---|---|---|
| MIT | MaterialKolor runtime components | Preserve copyright and license text. `LICENSES/MaterialKolor-MIT.txt` is included. |
| Apache-2.0 | Kotlin/Kotlinx, Compose Desktop, AndroidX/Jetpack JVM artifacts, Miuix, OkHttp/Okio, ZXing, SQLite JDBC, Skiko/JBR API and other resolved runtime components | Preserve license text, notices and upstream attribution; do not imply trademarks are granted. `LICENSES/Apache-2.0.txt` is included. |
| Public Domain | `org.json:json:20240303` | Upstream release declares Public Domain. See its release license. |
| GPL-2.0-with-Classpath-Exception | OpenJFX `javafx-base`, `javafx-graphics`, and `javafx-media` 25.0.2 (Windows classifier) | Maven metadata and OpenJFX identify GPL v2 with the Classpath Exception. The matching Windows runtime is included as a separate Gradle dependency; preserve OpenJFX attribution and license material. The local app image was inspected and contains the JavaFX jars and native media payload; the Inno script now includes the project/dependency notices, with CI smoke assertions for their presence. `LICENSES/GPL-2.0.txt` and `LICENSES/Classpath-Exception-2.0.txt` are included. |
| LGPL-2.1-or-later | `net.jthink:jaudiotagger:3.0.1` | Included as a separate runtime dependency in `app/jaudiotagger-3.0.1-<generated-hash>.jar` and referenced by the adjacent launcher configuration. Replace that file with a compatible JAR under the same filename. The matching Maven Central source JAR is included under `licenses/jaudiotagger-3.0.1-sources.jar`; SHA-256 is `D9C79A145944E6BC37579403843C9DA84CD81459E42C9877351802EE284F5858`. `LICENSES/LGPL-2.1.txt` is included. The installer smoke check confirms the JAR remains separately replaceable and on the application classpath. |
| LGPL-2.1-or-later and permissive native components | mpv/libmpv 0.41.0, FFmpeg 8.0.3, libass 0.17.5, libplacebo 7.360.1, FreeType 2.14.1, FriBidi 1.0.17, HarfBuzz 12.2.0, and MSYS2 Winpthreads | The release build disables FFmpeg GPL/version3/nonfree code, ships runtime DLLs separately under `libmpv/`, and includes pinned source archives, the exact matching MSYS2 Winpthreads source package, license texts, build recipe, import inventory, and toolchain package inventory. GCC runtime helpers are statically linked under the GCC Runtime Library Exception; the exception text is included. See `docs/testing/libmpv-runtime.md`. This inventory is a build gate, not a legal opinion. |
| LGPL-2.1-or-later **or** Apache-2.0 | `net.java.dev.jna:jna:5.19.1` | Upstream documents the Apache-2.0 alternative for v4+. This project chooses the Apache option and includes that license text. Preserve JNA notices in the packaged distribution. |
| GPL-2.0-with-Classpath-Exception | OpenJDK core files in the Temurin JDK 25 runtime used to build/package | This is not a Gradle `runtimeClasspath` dependency. The JDK has per-component legal notices; retain the generated runtime image's entire `legal/` directory and inspect it before redistribution. The base license and exception text are included for reference. |

Gradle 9.3.1 and its Wrapper are build-only Apache-2.0 tools and are not included in the application runtime image. The GPL-2.0 base text and Classpath Exception are included under `LICENSES/` for reference; the packaged Temurin runtime's per-module notices remain authoritative for redistribution.

The Gradle-resolved application runtime snapshot contains no dependency identified as plain GPL. The libmpv release configuration separately disables mpv GPL code and FFmpeg GPL/version3/nonfree options; the installer cannot be published until the generated import manifest and every actual DLL in the package are checked. That statement excludes build-host tools and the JDK runtime image, which has its own GPL plus Classpath Exception licensing. This is a source-level inventory, not a legal opinion.

## Primary references

- [Hachimi MIT license and pinned source](https://github.com/qingyueyin/Hachimi/tree/fafbc5e12ef4ce123a7e36a6a9e9d4553109ef91)
- [Miuix repository/license](https://github.com/compose-miuix-ui/miuix)
- [OkHttp repository/license](https://github.com/square/okhttp)
- [ZXing license notes](https://github.com/zxing/zxing/wiki/License-Questions)
- [JSON-java 20240303 license](https://github.com/stleary/JSON-java/blob/20240303/LICENSE)
- [OpenJFX project and license](https://github.com/openjdk/jfx)
- [OpenJFX 25.0.2 Maven metadata](https://central.sonatype.com/artifact/org.openjfx/javafx/25.0.2)
- [JNA license choices](https://github.com/java-native-access/jna/blob/master/README.md)
- [jaudiotagger 3.0.1 Maven metadata](https://central.sonatype.com/artifact/net.jthink/jaudiotagger/3.0.1)
- [SQLite JDBC project license](https://github.com/xerial/sqlite-jdbc)
- [Eclipse Temurin project and licensing](https://projects.eclipse.org/projects/adoptium.temurin)
- [Gradle Wrapper integrity guidance](https://docs.gradle.org/current/userguide/best_practices_security.html)

## Release gate

Before publishing an installer, regenerate both dependency inventories after any dependency change, review the actual packaged JARs/native runtime (not only POM declarations), verify the Inno smoke assertions for required notices and JavaFX Windows media payload, and verify the LGPL relinking/source obligations for the final Jaudiotagger packaging. The generated `legal/` files in the jpackage runtime image must be retained. Revisit whether Jaudiotagger remains appropriate for the product's licensing goals.
