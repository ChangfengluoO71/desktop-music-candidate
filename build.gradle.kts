import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.10.3"
}

group = "org.example.desktopmusic"
version = "0.1.2"

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("top.yukonga.miuix.kmp:miuix-ui-desktop:0.9.3")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.10.3")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("runGuiAcceptance") {
    group = "verification"
    description = "Launches the test-only desktop UI against local fixtures."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.qing.hachimi.desktop.DesktopGuiAcceptanceMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("hachimi.gui.acceptance.dir", layout.buildDirectory.dir("gui-acceptance-data-r3").get().asFile.absolutePath)
}

tasks.register<JavaExec>("runGuiAcceptanceM2") {
    group = "verification"
    description = "Launches the local M2 batch-download GUI harness using synthetic fixtures."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.qing.hachimi.desktop.DesktopGuiAcceptanceMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty(
        "hachimi.gui.acceptance.dir",
        providers.gradleProperty("guiAcceptanceDir")
            .orElse(layout.buildDirectory.dir("gui-acceptance-data-m2").map { it.asFile.absolutePath })
            .get(),
    )
}

tasks.register<JavaExec>("runLocalMusicGuiAcceptance") {
    group = "verification"
    description = "Opens a test-only local-music window with generated fixtures and no network or credentials."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.qing.hachimi.desktop.LocalMusicGuiAcceptanceMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty(
        "hachimi.localmusic.acceptance.dir",
        layout.buildDirectory.dir("local-music-gui-acceptance-data").get().asFile.absolutePath,
    )
}

compose.desktop {
    application {
        mainClass = "com.qing.hachimi.desktop.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        jvmArgs += "-Djava.net.useSystemProxies=true"
        nativeDistributions {
            // LocalMusicStore uses JDBC types at startup; jdeps missed this module
            // when creating the minimized runtime, which made the launcher fail.
            modules("java.sql")
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "DesktopMusicCandidate"
            packageVersion = project.version.toString()
            description = "Independent desktop music product candidate"
            vendor = "Independent project candidate"
            windows {
                // Candidate-only identity. Replace only after the public product brand is approved.
                upgradeUuid = "e1b24d8d-f6f8-429b-9f56-7d0c37c8f2b5"
                perUserInstall = true
                menuGroup = "Desktop Music Candidate"
            }
        }
    }
}

tasks.matching { it.name in setOf("run", "createRuntimeImage", "packageExe", "packageMsi") }
    .configureEach {
        doFirst {
            val current = JavaVersion.current()
            check(current.isCompatibleWith(JavaVersion.VERSION_21)) {
                "Desktop Music Candidate requires JDK 21 or newer to run or package; detected $current."
            }
        }
    }
