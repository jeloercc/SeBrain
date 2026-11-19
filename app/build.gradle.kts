import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The release key and its password live in the repo's `secrets/` folder, which git ignores:
 * `secrets/sebrain-release.jks` and `secrets/signing.properties` (SEBRAIN_STORE_PASSWORD,
 * SEBRAIN_KEY_ALIAS, SEBRAIN_KEY_PASSWORD). Null if anything is missing: then a release build
 * fails instead of coming out unsigned.
 */
val releaseSigning: Map<String, String>? = run {
    val keystore = rootProject.file("secrets/sebrain-release.jks")
    val file = rootProject.file("secrets/signing.properties")
    if (!keystore.isFile || !file.isFile) return@run null
    val properties = Properties().apply { file.inputStream().use { load(it) } }
    (listOf("SEBRAIN_STORE_PASSWORD", "SEBRAIN_KEY_ALIAS", "SEBRAIN_KEY_PASSWORD")
        .associateWith { properties.getProperty(it).orEmpty() } + ("SEBRAIN_STORE_FILE" to keystore.path))
        .takeIf { values -> values.values.none { it.isEmpty() } }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "packageRelease") {
        doFirst {
            if (releaseSigning == null) {
                throw GradleException(
                    "Release signing is not set up: put the key in secrets/sebrain-release.jks and SEBRAIN_STORE_PASSWORD, " +
                        "SEBRAIN_KEY_ALIAS and SEBRAIN_KEY_PASSWORD in secrets/signing.properties.",
                )
            }
        }
    }
}

android {
    namespace = "com.sebrain.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.sebrain.app"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The release key and its password: see releaseSigning at the top (secrets/, ignored by git).
    signingConfigs {
        create("release") {
            if (releaseSigning != null) {
                storeFile = file(releaseSigning.getValue("SEBRAIN_STORE_FILE"))
                storePassword = releaseSigning.getValue("SEBRAIN_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("SEBRAIN_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("SEBRAIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else null
            // R8 stays off: LiteRT-LM and SQLCipher reach classes through reflection and JNI.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // The Tensor NPU dispatch library is loaded from nativeLibraryDir, so the .so files must be extracted.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:vault"))
    implementation(project(":core:ml"))
    implementation(project(":core:net"))
    implementation(project(":ingest"))
    implementation(project(":cognition"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
