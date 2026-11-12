buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9.5.1 bundles Kotlin 2.2.0, and litertlm-android:0.18.1 is compiled with 2.4.0.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.library") version "9.5.1" apply false
    id("com.android.application") version "9.5.1" apply false
}
