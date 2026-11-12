plugins {
    id("com.android.library")
}

val generatedSqlcipherDir = layout.buildDirectory.dir("generated/sqlcipher")
val sqlcipherSources = rootProject.file("third_party/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/src")

val generateAmalgamation by tasks.registering(Exec::class) {
    description = "Generates the SQLCipher amalgamation."
    val script = file("scripts/generate-amalgamation.sh")
    inputs.file(script)
    inputs.dir(sqlcipherSources.resolve("src"))
    inputs.dir(sqlcipherSources.resolve("tool"))
    inputs.file(sqlcipherSources.resolve("VERSION"))
    outputs.dir(generatedSqlcipherDir)
    commandLine(
        script.path,
        generatedSqlcipherDir.get().asFile.path,
        layout.buildDirectory.dir("amalgamation").get().asFile.path,
    )
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateAmalgamation)
}

android {
    namespace = "com.sebrain.core.vault"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 36
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            ndkBuild {
                arguments("SEBRAIN_GENERATED_DIR=${generatedSqlcipherDir.get().asFile.path}")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main") {
        java.srcDir(rootProject.file("third_party/sqlcipher-android/sqlcipher/src/main/java"))
    }
}

dependencies {
    api(project(":core:crypto"))

    // Required by the upstream Java layer (SupportSQLite interfaces).
    api("androidx.sqlite:sqlite:2.7.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
