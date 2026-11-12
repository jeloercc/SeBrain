package com.sebrain.core.crypto

/** Failures while obtaining the vault key. Messages never contain key material. */
sealed class VaultKeyException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The database exists but its wrapped DEK does not. The database is left untouched. */
    class WrappedDekMissing(path: String) :
        VaultKeyException("Wrapped DEK missing at $path; the database was not modified")

    /** The wrapped DEK is malformed or failed authentication. The database is left untouched. */
    class WrappedDekCorrupted(path: String, detail: String, cause: Throwable? = null) :
        VaultKeyException("Wrapped DEK at $path is corrupted ($detail); the database was not modified", cause)

    /** A wrapped DEK exists but K_wrap is gone from the Keystore. */
    class WrapKeyMissing(alias: String) :
        VaultKeyException("Keystore key '$alias' is missing; the wrapped DEK cannot be used")

    /** K_wrap requires an unlocked device and the device is locked. */
    class DeviceLocked(cause: Throwable? = null) :
        VaultKeyException("The device is locked; the key cannot be used", cause)

    /** A segment references a K_inbox that no longer exists. */
    class InboxKeyMissing(alias: String) : VaultKeyException("Keystore key '$alias' is missing")

    /** A segment carries a key id this version does not know. */
    class UnknownInboxKey(keyId: Byte) : VaultKeyException("Unknown K_inbox key id $keyId")

    /** The screen is off. The vault is not opened then, because the later lock could not be observed. */
    class ScreenOff : VaultKeyException("The screen is off; the vault stays closed")

    /** The unwrapped DEK does not open the database. The database is left untouched. */
    class KeyMismatch(cause: Throwable? = null) :
        VaultKeyException("The DEK does not open this database; the database was not modified", cause)

    /** Any other Keystore failure. */
    class KeystoreFailure(cause: Throwable) :
        VaultKeyException("Keystore operation failed: ${cause.javaClass.simpleName}", cause)
}
