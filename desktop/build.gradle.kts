import java.util.Properties

val appVersionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersion = appVersionProps.getProperty("version")

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

sourceSets["main"].kotlin.srcDir("../ui/src")

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("net.java.dev.jna:jna:5.17.0")
}

compose.desktop {
    application {
        mainClass = "dev.anime.desktop.MainKt"
        jvmArgs += listOf(
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "-Danime.version=$appVersion",
        )
        nativeDistributions {
            packageName = "Anime"
            packageVersion = appVersion
        }
    }
}
