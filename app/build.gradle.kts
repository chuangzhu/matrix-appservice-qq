/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Kotlin application project to get you started.
 * For more details take a look at the 'Building Java & JVM projects' chapter in the Gradle
 * User Manual available at https://docs.gradle.org/7.4.2/userguide/building_java_projects.html
 */

plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.5.31"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.5.31"

    // Apply the application plugin to add support for building a CLI application in Java.
    application
}

tasks {
    compileKotlin {
       kotlinOptions { jvmTarget = JavaVersion.VERSION_11.toString() }
    }
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    api(platform("net.mamoe:mirai-bom:2.10.1"))
    api("net.mamoe:mirai-core-api")
    runtimeOnly("net.mamoe:mirai-core")

    implementation("net.folivo:trixnity-appservice:1.1.9")
    implementation("net.folivo:trixnity-client-api:1.1.9")
    implementation("io.ktor:ktor-server-netty:1.6.7")
    implementation("io.ktor:ktor-client-core:1.6.7")
    implementation("io.ktor:ktor-client-cio:1.6.7")
    implementation("org.slf4j:slf4j-simple:1.7.32")

    implementation("com.charleskorn.kaml:kaml:0.43.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation("org.jsoup:jsoup:1.15.1")

    implementation("org.xerial:sqlite-jdbc:3.30.1")
    implementation("org.postgresql:postgresql:42.2.2")

    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // This dependency is used by the application.
    implementation("com.google.guava:guava:30.1.1-jre")

    // Use the Kotlin test library.
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Use the Kotlin JUnit integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

application {
    // Define the main class for the application.
    mainClass.set("land.melty.matrixappserviceqq.AppKt")
}
