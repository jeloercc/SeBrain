# Builds libsqlcipher from the pinned upstream JNI sources and the generated amalgamation.
# The upstream Android.mk is not used, so the submodule stays unmodified.

SEBRAIN_JNI_DIR := $(call my-dir)
SEBRAIN_ROOT := $(abspath $(SEBRAIN_JNI_DIR)/../../../../..)
UPSTREAM_JNI := $(SEBRAIN_ROOT)/third_party/sqlcipher-android/sqlcipher/src/main/jni

include $(CLEAR_VARS)
LOCAL_PATH := $(UPSTREAM_JNI)/sqlcipher
LOCAL_MODULE := libsqlcipher

# Upstream default flags, without SQLITE_ENABLE_LOAD_EXTENSION.
LOCAL_CFLAGS := \
    -DSQLITE_HAS_CODEC \
    -DSQLCIPHER_CRYPTO_LIBTOMCRYPT \
    -DSQLITE_TEMP_STORE=2 \
    -DSQLITE_THREADSAFE=1 \
    -DSQLITE_ENABLE_COLUMN_METADATA \
    -DSQLITE_ENABLE_FTS3_PARENTHESIS \
    -DSQLITE_ENABLE_FTS4 \
    -DSQLITE_ENABLE_FTS4_UNICODE61 \
    -DSQLITE_ENABLE_FTS5 \
    -DSQLITE_ENABLE_MEMORY_MANAGEMENT \
    -DSQLITE_ENABLE_UNLOCK_NOTIFY \
    -DSQLITE_ENABLE_RTREE \
    -DSQLITE_SOUNDEX \
    -DHAVE_USLEEP \
    -DSQLITE_ENABLE_STAT3 \
    -DSQLITE_ENABLE_STAT4 \
    -DSQLITE_ENABLE_JSON1 \
    -DSQLITE_ENABLE_EXPLAIN_COMMENTS \
    -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1 \
    -DSQLITE_MAX_VARIABLE_NUMBER=99999 \
    -DSQLITE_DEFAULT_JOURNAL_SIZE_LIMIT=1048576 \
    -DSQLITE_ENABLE_SESSION \
    -DSQLITE_ENABLE_PREUPDATE_HOOK \
    -DSQLITE_ENABLE_DBSTAT_VTAB \
    -DSQLITE_ENABLE_SNAPSHOT \
    -DSQLITE_USE_URI \
    -DSQLITE_EXTRA_INIT=sqlcipher_extra_init \
    -DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown

ifeq ($(APP_OPTIM),release)
LOCAL_CFLAGS += -DNDEBUG
endif

LOCAL_CPPFLAGS += -Wno-conversion-null

LOCAL_SRC_FILES := \
    android_database_SQLiteCommon.cpp \
    android_database_SQLiteConnection.cpp \
    android_database_CursorWindow.cpp \
    android_database_SQLiteGlobal.cpp \
    android_database_SQLiteDebug.cpp \
    JNIHelp.cpp \
    JniConstants.cpp \
    JNIString.cpp \
    CursorWindow.cpp \
    $(SEBRAIN_GENERATED_DIR)/sqlite3.c

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/nativehelper \
    $(SEBRAIN_GENERATED_DIR)

LOCAL_LDLIBS := -ldl -llog
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
LOCAL_STATIC_LIBRARIES := static-libtomcrypt
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
include $(UPSTREAM_JNI)/libtomcrypt/Android.mk
