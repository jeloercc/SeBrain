package com.sebrain.core.vault

/** Loads the native vault library once per process. */
object VaultNative {
    private var loaded = false

    @Synchronized
    fun load() {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}
