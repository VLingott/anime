plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("io.ktor:ktor-client-core:3.3.1")
    api("io.ktor:ktor-client-okhttp:3.3.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jsoup:jsoup:1.21.2")
    compileOnly("org.xerial:sqlite-jdbc:3.50.3.0")
}
