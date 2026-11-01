package dev.kinash.smartscales.core.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One frame in the [BluetoothLog].
 *
 * @property address the device address as Android reports it; replaced with an alias on export.
 * @property protocol the driver's protocol when the device was recognised as a scale, otherwise null.
 * @property data the frame as received, in hex: the whole advertisement, or the value of a GATT notification.
 */
data class LogEntry(
    val timeMs: Long,
    val address: String,
    val rssi: Int,
    val name: String?,
    val protocol: String?,
    val kind: Kind,
    val data: String,
) {
    enum class Kind { ADV, GATT }

    /** The frame as bytes. */
    fun bytes(): ByteArray = data.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Raw Bluetooth frames for reports: someone with an unsupported or misread scale records a weighing and
 * sends the file, and the driver is fixed without the device. Frames are kept as received, because where
 * the weight is is known only for parsed protocols. Recorded only while [recording] is on: frames of
 * recognised scales always, other devices nearby only in [allDevices] mode. A frame repeating the last one
 * of its device is skipped for [REPEAT_MS].
 *
 * The log lives in [dir], private to the app, with real addresses; [export] replaces them with aliases
 * (`scale-1`, `device-1`) that are stable within the file. All file work, the recording switch included,
 * runs in order on [scope].
 */
class BluetoothLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private sealed interface Command {
        data class Write(val entry: LogEntry) : Command
        data class SetRecording(val on: Boolean) : Command
        data object Clear : Command
        class Export(
            val out: Writer,
            val header: List<String>,
            val zone: ZoneId,
            val identities: ((LogEntry) -> Collection<String>)?,
            val done: CompletableDeferred<Unit>,
        ) : Command
    }

    private val file = File(dir, "bluetooth.log")
    private val recordingFlag = File(dir, "recording")
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val lastByAddress = HashMap<String, LogEntry>()
    private val aliases = HashMap<String, String>()

    @Volatile
    private var switchedMeanwhile = false

    private val _recording = MutableStateFlow(false)

    /** Read from disk in the background at start, so it is briefly off until then. */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _allDevices = MutableStateFlow(false)

    /** Also devices that are not recognised scales; for the current session only, never remembered. */
    val allDevices: StateFlow<Boolean> = _allDevices.asStateFlow()

    private val _recent = MutableStateFlow<List<AliasedEntry>>(emptyList())

    /** The newest entries, oldest first, for showing on screen. */
    val recent: StateFlow<List<AliasedEntry>> = _recent.asStateFlow()

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** An entry with the alias of its device, as the export will show it. */
    data class AliasedEntry(val entry: LogEntry, val alias: String)

    init {
        scope.launch {
            if (!switchedMeanwhile) _recording.value = recordingFlag.exists()
            load()
            for (command in commands) {
                when (command) {
                    is Command.Write -> append(command.entry)
                    is Command.SetRecording -> {
                        dir.mkdirs()
                        if (command.on) recordingFlag.createNewFile() else recordingFlag.delete()
                    }
                    Command.Clear -> clearNow()
                    is Command.Export -> {
                        runCatching { exportNow(command) }
                            .onSuccess { command.done.complete(Unit) }
                            .onFailure { command.done.completeExceptionally(it) }
                    }
                }
            }
        }
    }

    fun setRecording(on: Boolean) {
        switchedMeanwhile = true
        _recording.value = on
        if (!on) _allDevices.value = false
        commands.trySend(Command.SetRecording(on))
    }

    fun setAllDevices(on: Boolean) {
        _allDevices.value = on && _recording.value
    }

    /** Records an advertisement when recording; [protocol] is the scale protocol it was recognised as. */
    fun record(advertisement: ScannedAdvertisement, protocol: String?) {
        if (!_recording.value || (protocol == null && !_allDevices.value)) return
        val entry = LogEntry(
            timeMs = clock(),
            address = advertisement.address.uppercase(),
            rssi = advertisement.rssi,
            name = advertisement.name,
            protocol = protocol,
            kind = LogEntry.Kind.ADV,
            data = advertisingPart(advertisement.record).toHex(),
        )
        synchronized(lastByAddress) {
            val last = lastByAddress[entry.address]
            if (last != null && last.data == entry.data && entry.timeMs - last.timeMs < REPEAT_MS) return
            lastByAddress[entry.address] = entry
        }
        commands.trySend(Command.Write(entry))
    }

    fun clear() {
        synchronized(lastByAddress) { lastByAddress.clear() }
        commands.trySend(Command.Clear)
    }

    /**
     * Writes the log with aliases instead of addresses, after [header] lines, times in [zone]. With
     * [identities], the addresses it gives for an entry (as `AA:BB:CC:DD:EE:FF`) are zeroed inside the frame
     * in both byte orders, and so are their last four bytes alone: an encrypted `C0` frame carries only those
     * in the clear (an unencrypted one carries the whole MAC).
     */
    suspend fun export(
        out: Writer,
        header: List<String>,
        zone: ZoneId = ZoneId.systemDefault(),
        identities: ((LogEntry) -> Collection<String>)? = null,
    ) {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Export(out, header, zone, identities, done))
        done.await()
    }

    private fun readEntries(): List<LogEntry> = if (file.exists()) file.readLines().mapNotNull(::parse) else emptyList()

    private fun load() {
        val entries = readEntries()
        aliases.clear()
        aliases.putAll(assignAliases(entries))
        _count.value = entries.size
        _recent.value = entries.takeLast(RECENT).map { AliasedEntry(it, aliases.getValue(it.address)) }
    }

    private fun append(entry: LogEntry) {
        dir.mkdirs()
        file.appendText(format(entry) + "\n")
        val known = aliases[entry.address]
        val alias = when {
            known == null || (entry.protocol != null && known.startsWith(DEVICE)) ->
                nextAlias(entry.protocol != null, aliases.values).also { aliases[entry.address] = it }
            else -> known
        }
        _count.update { it + 1 }
        _recent.update { recent ->
            // A device recognised as a scale later gets its scale alias in the earlier lines too
            val renamed = if (alias == known) recent else recent.map { if (it.entry.address == entry.address) it.copy(alias = alias) else it }
            (renamed + AliasedEntry(entry, alias)).takeLast(RECENT)
        }
        if (file.length() > MAX_BYTES) {
            // Keep the newer half; aliases follow what is left
            val lines = file.readLines()
            file.writeText(lines.takeLast(lines.size / 2).joinToString("") { "$it\n" })
            load()
        }
    }

    private fun clearNow() {
        file.delete()
        load()
    }

    private fun exportNow(command: Command.Export) {
        val entries = readEntries()
        val exportAliases = assignAliases(entries)
        val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(command.zone)
        val out = command.out
        out.write("# SmartScales Bluetooth log\n")
        command.header.forEach { out.write("# $it\n") }
        out.write("# Device addresses are replaced with aliases; data is each frame as received, in hex")
        out.write(if (command.identities != null) ", with device addresses inside frames zeroed.\n" else ".\n")
        out.write("# time\tdevice\trssi\tprotocol\tkind\tname\tdata\n")
        for (entry in entries) {
            val data = command.identities?.let { hideAddresses(entry.data, it(entry)) } ?: entry.data
            val fields = listOf(
                time.format(Instant.ofEpochMilli(entry.timeMs)),
                exportAliases.getValue(entry.address),
                entry.rssi.toString(),
                entry.protocol.orEmpty(),
                entry.kind.name,
                entry.name.orEmpty(),
                data,
            )
            out.write(fields.joinToString("\t") + "\n")
        }
        out.flush()
    }

    private fun format(entry: LogEntry): String = listOf(
        entry.timeMs.toString(),
        entry.address,
        entry.rssi.toString(),
        entry.protocol.orEmpty(),
        entry.kind.name,
        entry.name.orEmpty().replace(Regex("[\\t\\r\\n]"), " "),
        entry.data,
    ).joinToString("\t")

    private fun parse(line: String): LogEntry? {
        val f = line.split('\t')
        if (f.size != FIELDS) return null
        return LogEntry(
            timeMs = f[0].toLongOrNull() ?: return null,
            address = f[1],
            rssi = f[2].toIntOrNull() ?: return null,
            protocol = f[3].ifEmpty { null },
            kind = LogEntry.Kind.entries.firstOrNull { it.name == f[4] } ?: return null,
            name = f[5].ifEmpty { null },
            data = f[6],
        )
    }

    companion object {
        /** A device repeating the same frame is written again after this long, to show it is still there. */
        const val REPEAT_MS = 10_000L

        /** Entries kept for the screen. */
        const val RECENT = 200

        /** The log file is cut to its newer half beyond this size. */
        const val MAX_BYTES = 2L * 1024 * 1024

        private const val FIELDS = 7
        private const val DEVICE = "device-"
    }
}

/**
 * Aliases by address in the order devices first appear: `scale-N` for a device recognised as a scale in
 * any of its entries, `device-N` for the rest.
 */
internal fun assignAliases(entries: List<LogEntry>): Map<String, String> {
    val scales = entries.filter { it.protocol != null }.mapTo(HashSet()) { it.address }
    val aliases = LinkedHashMap<String, String>()
    for (entry in entries) {
        if (entry.address !in aliases) aliases[entry.address] = nextAlias(entry.address in scales, aliases.values)
    }
    return aliases
}

/**
 * The next free alias: one above the highest taken, so a number freed when a device becomes a scale is
 * not given to another device.
 */
private fun nextAlias(scale: Boolean, taken: Collection<String>): String {
    val prefix = if (scale) "scale-" else "device-"
    val highest = taken.mapNotNull { if (it.startsWith(prefix)) it.removePrefix(prefix).toIntOrNull() else null }.maxOrNull() ?: 0
    return "$prefix${highest + 1}"
}

/** [data] in hex with each of [addresses] zeroed in both byte orders, and also its last four bytes alone. */
internal fun hideAddresses(data: String, addresses: Collection<String>): String {
    var result = data
    for (address in addresses) {
        val bytes = address.split(':').filter { it.length == 2 }
        if (bytes.size != MAC_BYTES) continue
        val tail = bytes.takeLast(MAC_TAIL_BYTES)
        for (sequence in listOf(bytes, bytes.reversed(), tail, tail.reversed())) {
            // Bytes take three characters each, so a match always starts on a byte
            result = result.replace(sequence.joinToString(" ").uppercase(), sequence.joinToString(" ") { "00" })
        }
    }
    return result
}

private const val MAC_BYTES = 6
private const val MAC_TAIL_BYTES = 4

/**
 * The advertising part of a scan record: Android pads it with zeros up to its full length, and an AD
 * structure of length 0 ends it.
 */
internal fun advertisingPart(record: ByteArray): ByteArray {
    var i = 0
    while (i < record.size) {
        val length = record[i].toInt() and 0xFF
        if (length == 0) break
        i += 1 + length
    }
    return record.copyOf(minOf(i, record.size))
}

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
