package dev.kinash.smartscales.core.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** What reading a backup file found. */
sealed interface BackupRead {
    data class Ok(val backup: Backup) : BackupRead

    /** The file is a backup locked with a password, and none was given. */
    data object NeedsPassword : BackupRead

    /** The password does not open it, or the file was changed since. */
    data object WrongPassword : BackupRead

    /** A backup of a newer app, which this one cannot read. */
    data class TooNew(val version: Int) : BackupRead

    /** Not a backup of this app, or a damaged one. */
    data object NotABackup : BackupRead
}

/**
 * The backup file: [Backup] as JSON, or, with a password, that JSON locked with AES-256-GCM under a key made
 * from the password with PBKDF2-HMAC-SHA256 and a random salt. A locked file is JSON too, saying how it is
 * locked, so that a later version can change the parameters and still read the older files:
 * `{"format": "smartscales-backup", "version": 1, "encryption": {...}, "data": "<Base64>"}`.
 */
object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        // The format and version are defaults and must be written; absent values are left out
        encodeDefaults = true
        explicitNulls = false
    }

    /** As OWASP advises for PBKDF2-HMAC-SHA256, lowered for phones: a second or so on a mid-range one. */
    const val ITERATIONS = 310_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES-256-GCM"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    /** The file of [backup]; locked with [password] when there is one. */
    fun encode(backup: Backup, password: CharArray? = null, iterations: Int = ITERATIONS): ByteArray {
        val plain = json.encodeToString(Backup.serializer(), backup).toByteArray()
        if (password == null || password.isEmpty()) return plain
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        val locked = Locked(
            encryption = Encryption(CIPHER, KDF, iterations, base64(salt), base64(iv)),
            data = base64(cipher.doFinal(plain)),
        )
        return json.encodeToString(Locked.serializer(), locked).toByteArray()
    }

    /** Reads a backup file; [password] opens a locked one. */
    fun decode(bytes: ByteArray, password: CharArray? = null): BackupRead {
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (e: SerializationException) {
            return BackupRead.NotABackup
        } catch (e: IllegalArgumentException) {
            return BackupRead.NotABackup
        }
        if (root["format"]?.jsonPrimitive?.contentOrNull != Backup.FORMAT) return BackupRead.NotABackup
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: return BackupRead.NotABackup
        if (version > Backup.VERSION) return BackupRead.TooNew(version)
        val plain = if ("data" in root) {
            if (password == null || password.isEmpty()) return BackupRead.NeedsPassword
            val inner = unlock(root, password) ?: return BackupRead.WrongPassword
            // The locked backup has its own format and version, which must agree with the wrapper
            if (inner["format"]?.jsonPrimitive?.contentOrNull != Backup.FORMAT) return BackupRead.NotABackup
            val innerVersion = inner["version"]?.jsonPrimitive?.intOrNull ?: return BackupRead.NotABackup
            if (innerVersion > Backup.VERSION) return BackupRead.TooNew(innerVersion)
            inner
        } else {
            root
        }
        return try {
            BackupRead.Ok(json.decodeFromJsonElement(Backup.serializer(), plain))
        } catch (e: SerializationException) {
            BackupRead.NotABackup
        } catch (e: IllegalArgumentException) {
            BackupRead.NotABackup
        }
    }

    /** The JSON inside a locked file; null when [password] does not open it. */
    private fun unlock(root: JsonObject, password: CharArray): JsonObject? = try {
        val locked = json.decodeFromJsonElement(Locked.serializer(), root)
        val encryption = locked.encryption
        // A file asking for an endless key derivation is not one this app made
        if (encryption.cipher != CIPHER || encryption.kdf != KDF || encryption.iterations !in 1..MAX_ITERATIONS) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = key(password, unbase64(encryption.salt), encryption.iterations)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, unbase64(encryption.iv)))
            json.parseToJsonElement(cipher.doFinal(unbase64(locked.data)).decodeToString()).jsonObject
        }
    } catch (e: GeneralSecurityException) {
        // GCM's tag does not match: another password, or the file was changed
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unbase64(text: String): ByteArray = Base64.getDecoder().decode(text)

    @kotlinx.serialization.Serializable
    private data class Locked(
        val format: String = Backup.FORMAT,
        val version: Int = Backup.VERSION,
        val encryption: Encryption,
        val data: String,
    )

    @kotlinx.serialization.Serializable
    private data class Encryption(val cipher: String, val kdf: String, val iterations: Int, val salt: String, val iv: String)
}
