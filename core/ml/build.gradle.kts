plugins {
    id("com.android.library")
}

android {
    namespace = "com.sebrain.core.ml"
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
    // Pinned: 0.19.1 fails with the Tensor NPU runtime 2.3.2. Do not upgrade without retesting the NPU.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.2")

    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json.
    testImplementation("org.json:json:20240303")
}
