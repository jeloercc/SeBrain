plugins {
    id("com.android.application")
}

android {
    namespace = "com.sebrain.diag"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.sebrain.diag"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // La librería de dispatch de la NPU de Tensor se carga desde nativeLibraryDir:
    // hace falta que el instalador extraiga los .so.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.2")
    implementation("androidx.core:core-ktx:1.17.0")
}
