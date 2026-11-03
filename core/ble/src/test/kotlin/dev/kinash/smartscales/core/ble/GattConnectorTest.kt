package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.protocol.ScaleUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class GattConnectorTest {
    private val service = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val rx = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val tx = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val context = GattContext(Instant.parse("2026-11-03T08:00:00Z"), ScaleUnit.KILOGRAM, user = null)

    /** A scale that sends [frames] once subscribed and then goes to sleep. */
    private inner class FakeLink(private val frames: List<ByteArray>) : GattLink, GattSession {
        val operations = mutableListOf<String>()
        var released = false
        private val channel = Channel<GattNotification>(Channel.UNLIMITED)

        override val session: GattSession get() = this
        override val notifications: Flow<GattNotification> = channel.receiveAsFlow()

        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true

        override suspend fun subscribe(service: UUID, characteristic: UUID) {
            operations += "subscribe $characteristic"
            // The scale answers at once, before anyone reads: these must not be lost
            frames.forEach { channel.send(GattNotification(characteristic, it)) }
            channel.close()
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
            operations += "write $characteristic ${value.toHex()}"
        }

        override suspend fun release() {
            released = true
        }
    }

    /** Writes a command, subscribes, and turns each notification into a live reading with that many kilograms. */
    private class EchoProtocol(private val failStart: Boolean = false) : GattProtocol {
        override suspend fun start(session: GattSession, context: GattContext) {
            if (failStart) throw GattException("No such characteristic")
            session.write(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"), UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"), byteArrayOf(1, 0))
            session.subscribe(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"), UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"))
        }

        override fun onNotification(notification: GattNotification): List<GattEvent> =
            listOf(GattEvent.Live(ScaleReading("AA:BB:CC:DD:EE:FF", weight = null, measureSeqNo = notification.value[0].toInt())))
    }

    private class Logged(val characteristic: UUID, val data: String, val outgoing: Boolean)

    @Test
    fun runsTheProtocolAndReportsWhatTheScaleSendsUntilItSleeps() = runTest {
        val link = FakeLink(listOf(byteArrayOf(1), byteArrayOf(2)))
        val log = mutableListOf<Logged>()
        val connector = GattConnector({ link }, { _, _, characteristic, value, outgoing -> log += Logged(characteristic, value.toHex(), outgoing) })

        val updates = connector.connect("AA:BB", "OKOK_CLOUD_V4", EchoProtocol(), context).toList()

        assertEquals(ConnectionUpdate.Connecting(1, 2), updates[0])
        assertEquals(ConnectionUpdate.Connected, updates[1])
        assertEquals(listOf(1, 2), updates.filterIsInstance<ConnectionUpdate.Event>().map { it.event.reading.measureSeqNo })
        assertEquals(ConnectionUpdate.Disconnected, updates.last())
        assertEquals(listOf("write $tx 01 00", "subscribe $rx"), link.operations)
        assertTrue(link.released)
        // The command out and both frames in, in order
        assertEquals(listOf(true, false, false), log.map { it.outgoing })
        assertEquals(listOf(tx, rx, rx), log.map { it.characteristic })
    }

    @Test
    fun aFailedConnectionIsTriedAgain() = runTest {
        var tries = 0
        val link = FakeLink(emptyList())
        val connector = GattConnector({
            tries++
            if (tries == 1) throw GattException("Timeout") else link
        })

        val updates = connector.connect("AA:BB", "OKOK_CLOUD_V4", EchoProtocol(), context).toList()

        assertEquals(2, tries)
        assertEquals(ConnectionUpdate.Connecting(2, 2), updates[1])
        assertEquals(ConnectionUpdate.Connected, updates[2])
    }

    @Test
    fun givesUpAfterAllTries() = runTest {
        val connector = GattConnector({ throw GattException("Status 133", 133) })
        val updates = connector.connect("AA:BB", "OKOK_CLOUD_V4", EchoProtocol(), context).toList()
        val failed = updates.last() as ConnectionUpdate.Failed
        assertEquals(133, failed.cause.status)
        assertEquals(3, updates.size)
    }

    @Test
    fun noSecondTryWhenTryingAgainCannotHelp() = runTest {
        var tries = 0
        val connector = GattConnector({
            tries++
            throw GattException("Bluetooth is off", retryable = false)
        })
        val updates = connector.connect("AA:BB", "OKOK_CLOUD_V4", EchoProtocol(), context).toList()
        assertEquals(1, tries)
        assertTrue(updates.last() is ConnectionUpdate.Failed)
    }

    @Test
    fun aProtocolThatCannotStartFailsAndReleasesTheConnection() = runTest {
        val link = FakeLink(listOf(byteArrayOf(1)))
        val updates = GattConnector({ link }).connect("AA:BB", "OKOK_CLOUD_V4", EchoProtocol(failStart = true), context).toList()
        assertTrue(updates.last() is ConnectionUpdate.Failed)
        assertTrue(updates.none { it is ConnectionUpdate.Connected })
        assertTrue(link.released)
    }

    @Test
    fun characteristicsAreLoggedShort() {
        assertEquals("FA9C", shortUuid(UUID.fromString("0000fa9c-0000-1000-8000-00805f9b34fb")))
        assertEquals("D618D001-6000-1000-8000-000000000000", shortUuid(UUID.fromString("d618d001-6000-1000-8000-000000000000")))
    }
}
