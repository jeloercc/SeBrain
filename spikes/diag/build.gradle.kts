buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9.5.1 bundles Kotlin 2.2.0, and genai-prompt:1.0.0-beta5 is compiled with 2.4.0.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
}
