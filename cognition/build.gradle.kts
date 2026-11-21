plugins {
    id("com.android.library")
}

android {
    namespace = "com.sebrain.cognition"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:vault"))

    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json.
    testImplementation("org.json:json:20240303")
}
