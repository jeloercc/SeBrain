#include <jni.h>

#include "sqlite3.h"
#include "sqlite-vec.h"

/*
 * The upstream JNI calls sqlite3_enable_load_extension(db, 1) on every connection, and the
 * function does not exist with SQLITE_OMIT_LOAD_EXTENSION. This stub keeps the upstream code
 * linking; extension loading stays unavailable.
 */
int sqlite3_enable_load_extension(sqlite3 *db, int onoff) {
    (void) db;
    (void) onoff;
    return SQLITE_ERROR;
}

/*
 * Registers sqlite-vec for every connection opened afterwards.
 * SQLITE_EXTRA_INIT cannot be used: SQLCipher already sets it.
 */
JNIEXPORT jint JNICALL
Java_com_sebrain_core_vault_VaultNative_registerExtensions(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return sqlite3_auto_extension((void (*)(void)) sqlite3_vec_init);
}
