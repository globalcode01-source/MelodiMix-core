plugins {
    kotlin("jvm") version "2.4.0"
    `java-library`
}

group = "com.melodi.audio"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // ChunkMap uses org.json (built into Android; a regular dependency on the JVM)
    implementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}

