plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.anime.MainKt")
    applicationName = "anime-server"
}

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
    implementation("io.ktor:ktor-server-core:3.3.1")
    implementation("io.ktor:ktor-server-cio:3.3.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.1")
    implementation("io.ktor:ktor-server-cors:3.3.1")
    implementation("io.ktor:ktor-server-status-pages:3.3.1")
    implementation("io.ktor:ktor-client-core:3.3.1")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}
