package com.sebrain.core.vault

/** Loads the native vault library and registers its SQLite extensions once per process. */
object VaultNative {
    private var loaded = false

    private external fun registerExtensions(): Int

    @Synchronized
    fun load() {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            val rc = registerExtensions()
            check(rc == 0) { "sqlite3_auto_extension failed: $rc" }
            loaded = true
        }
    }
}
