#include <jni.h>

#include "sqlite3.h"
#include "sqlite-vec.h"

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
