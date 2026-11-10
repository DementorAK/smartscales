package dev.kinash.smartscales.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.BuildConfig
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.backup.Backup
import dev.kinash.smartscales.core.data.backup.BackupCodec
import dev.kinash.smartscales.core.data.backup.BackupRead
import dev.kinash.smartscales.core.data.backup.BackupRepository
import dev.kinash.smartscales.core.data.backup.BrokenBackupException
import dev.kinash.smartscales.indicators.GrowthTables
import dev.kinash.smartscales.ui.common.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** A backup file being opened: what it holds, or what is needed to open it. */
sealed interface PendingRestore {
    /** The file is locked; [wrong] after a password that did not open it. */
    data class Password(val wrong: Boolean = false) : PendingRestore

    /** Read: the person confirms replacing everything with it. */
    data class Confirm(val backup: Backup) : PendingRestore {
        val createdAt: Instant? = runCatching { Instant.parse(backup.createdAt) }.getOrNull()
    }
}

/** The backup of the whole phone into a file and back (requirements §4.3). */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backups: BackupRepository,
    private val growth: GrowthTables,
    private val clock: Clock,
) : ViewModel() {
    private val _busy = MutableStateFlow(false)

    /** A backup is being written, read or restored: the buttons wait. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)

    /** What the last action came to, shown once. */
    val message: StateFlow<Int?> = _message.asStateFlow()

    private val _pending = MutableStateFlow<PendingRestore?>(null)
    val pending: StateFlow<PendingRestore?> = _pending.asStateFlow()

    /** The opened file, kept while its password is asked for. */
    private var opened: ByteArray? = null

    fun messageShown() {
        _message.value = null
    }

    /** Writes the backup into [uri], locked with [password] when one is given. */
    fun write(uri: Uri, password: String) = work {
        val result = runCatching {
            val files = listOfNotNull(growth.csv()?.let { GrowthTables.FILE_NAME to it }).toMap()
            val backup = backups.backup(clock.instant(), BuildConfig.VERSION_NAME, files)
            val bytes = withContext(Dispatchers.Default) { BackupCodec.encode(backup, password.takeIf { it.isNotEmpty() }?.toCharArray()) }
            withContext(Dispatchers.IO) {
                // The file was just created and is empty; some document providers refuse "wt", which only adds truncation
                val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: context.contentResolver.openOutputStream(uri, "w")
                stream?.use { it.write(bytes) } ?: throw IOException("No stream for $uri")
            }
        }
        _message.value = if (result.isSuccess) R.string.backup_written else R.string.backup_write_failed
    }

    /** Opens the backup in [uri]: asks for its password, or for the confirmation to restore it. */
    fun open(uri: Uri) = work {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // Far larger than a family's years of weighings; a bigger file is not a backup
                    readAtMost(input, MAX_BYTES + 1).takeIf { it.size <= MAX_BYTES }
                }
            }.getOrNull()
        }
        if (bytes == null) {
            _message.value = R.string.backup_read_failed
            return@work
        }
        opened = bytes
        read(null)
    }

    /** Tries [password] on the opened file; a String from the field, which cannot be wiped from memory, accepted here. */
    fun unlock(password: String) = work { read(password.toCharArray()) }

    fun cancel() {
        opened = null
        _pending.value = null
    }

    /** Replaces everything with the confirmed backup. */
    fun restore() {
        val confirm = _pending.value as? PendingRestore.Confirm ?: return
        _pending.value = null
        opened = null
        work {
            val result = runCatching {
                withContext(NonCancellable) {
                    backups.restore(confirm.backup)
                    growth.restore(confirm.backup.files[GrowthTables.FILE_NAME])
                }
            }
            _message.value = when {
                result.isSuccess -> R.string.backup_restored
                result.exceptionOrNull() is BrokenBackupException -> R.string.backup_broken
                else -> R.string.backup_restore_failed
            }
        }
    }

    private suspend fun read(password: CharArray?) {
        val bytes = opened ?: return
        val read = try {
            withContext(Dispatchers.Default) { BackupCodec.decode(bytes, password) }
        } catch (e: OutOfMemoryError) {
            // The JSON tree of a large file takes several times its size: a phone short of memory says so instead of failing
            BackupRead.NotABackup
        }
        when (read) {
            is BackupRead.Ok -> _pending.value = PendingRestore.Confirm(read.backup)
            BackupRead.NeedsPassword -> _pending.value = PendingRestore.Password()
            BackupRead.WrongPassword -> _pending.value = PendingRestore.Password(wrong = true)
            is BackupRead.TooNew -> {
                cancel()
                _message.value = R.string.backup_too_new
            }
            BackupRead.NotABackup -> {
                cancel()
                _message.value = R.string.backup_not_a_backup
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        /** A family's years of weighings take a megabyte or two; the limit keeps reading within a phone's memory. */
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
