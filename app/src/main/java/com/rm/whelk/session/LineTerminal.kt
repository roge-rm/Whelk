package com.rm.whelk.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rm.whelk.core.Codec
import com.rm.whelk.core.ConnectionType
import com.rm.whelk.core.FlowControl
import com.rm.whelk.core.LineOutput
import com.rm.whelk.core.LineSettings
import com.rm.whelk.core.Parity
import com.rm.whelk.core.StopBits
import com.termux.terminal.StreamSession
import com.termux.terminal.TerminalSessionClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A terminal on a USB serial adapter, a Bluetooth serial device or a raw TCP socket. */
class LineTerminal(
    private val context: Context,
    private val session: Session,
    transcriptRows: Int,
    client: TerminalSessionClient,
) : StreamSession(transcriptRows, client) {
    @Volatile private var link: Closeable? = null
    @Volatile private var stopped = false

    override fun connect(columns: Int, rows: Int): Streams {
        val conn = session.connection ?: throw IllegalStateException("No connection")
        val (closer, streams) = when (conn.type) {
            ConnectionType.SERIAL -> Usb.open(context, conn.device, conn.line) { feed(it) }
            ConnectionType.BLUETOOTH -> Bluetooth.open(context, conn.device) { feed(it) }
            else -> {
                feed("Connecting to ${conn.host}:${conn.port}…")
                val socket = Socket()
                socket.connect(InetSocketAddress(conn.host.trim(), conn.port), 20_000)
                socket.tcpNoDelay = true
                socket to Streams(socket.getInputStream(), socket.getOutputStream())
            }
        }
        link = closer
        if (stopped) {
            closer.close()
            throw IllegalStateException("Cancelled")
        }
        feed("\u001b[2K\r")
        session.state.value = SessionState.OPEN
        Sessions.main.post { Sessions.changed() }
        val out = LineOutput(Codec.output(streams.out, conn.charset, conn.backspace), conn.line) { bytes -> feed(bytes, 0, bytes.size) }
        return Streams(Codec.input(streams.`in`, conn.charset), out)
    }

    override fun onResize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {}

    override fun onFinish() {
        stopped = true
        val l = link
        Sessions.io.execute { runCatching { l?.close() } }
        if (l == null) finished("\r\n[Disconnected]")
    }

    override fun onClosed() {
        runCatching { link?.close() }
    }

    override fun closedMessage(error: Exception?): String {
        if (stopped) return "\r\n[Disconnected]"
        return super.closedMessage(error)
    }
}

/** USB serial adapters, through usb-serial-for-android's drivers. */
object Usb {
    private const val ACTION_PERMISSION = "com.rm.whelk.USB_PERMISSION"

    class Adapter(val driver: UsbSerialDriver) {
        val device: UsbDevice get() = driver.device
        val id: String get() = String.format("%04x:%04x", device.vendorId, device.productId)
        val name: String
            get() = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifEmpty {
                driver.javaClass.simpleName.removeSuffix("SerialDriver") + " " + id
            }
    }

    fun adapters(context: Context): List<Adapter> {
        val um = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(um).map { Adapter(it) }
    }

    /** Opens the adapter with id [device], or the first one if blank, asking for permission first. */
    fun open(context: Context, device: String, line: LineSettings, show: (String) -> Unit): Pair<Closeable, StreamSession.Streams> {
        val um = context.getSystemService(UsbManager::class.java) ?: throw IOException("No USB on this device")
        val all = adapters(context)
        val adapter = all.firstOrNull { device.isNotEmpty() && it.id == device } ?: all.firstOrNull { device.isEmpty() }
            ?: throw IOException(if (all.isEmpty()) "No USB serial adapter plugged in" else "That adapter isn't plugged in")
        show("Opening ${adapter.name}…")
        if (!um.hasPermission(adapter.device) && !askPermission(context, um, adapter.device)) {
            throw IOException("Whelk wasn't allowed to use ${adapter.name}")
        }
        val connection = um.openDevice(adapter.device) ?: throw IOException("Couldn't open ${adapter.name}")
        val port = adapter.driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(
                line.baud, line.dataBits,
                when (line.stopBits) {
                    StopBits.ONE -> UsbSerialPort.STOPBITS_1
                    StopBits.ONE_HALF -> UsbSerialPort.STOPBITS_1_5
                    StopBits.TWO -> UsbSerialPort.STOPBITS_2
                },
                when (line.parity) {
                    Parity.NONE -> UsbSerialPort.PARITY_NONE
                    Parity.ODD -> UsbSerialPort.PARITY_ODD
                    Parity.EVEN -> UsbSerialPort.PARITY_EVEN
                    Parity.MARK -> UsbSerialPort.PARITY_MARK
                    Parity.SPACE -> UsbSerialPort.PARITY_SPACE
                },
            )
            val flow = when (line.flow) {
                FlowControl.NONE -> UsbSerialPort.FlowControl.NONE
                FlowControl.RTS_CTS -> UsbSerialPort.FlowControl.RTS_CTS
                FlowControl.XON_XOFF -> UsbSerialPort.FlowControl.XON_XOFF_INLINE
            }
            if (flow != UsbSerialPort.FlowControl.NONE) {
                if (flow !in port.supportedFlowControl) throw IOException("This adapter can't do ${line.flow.label}")
                port.flowControl = flow
            }
            // Many boards wait for DTR, and some reset on it, as an Arduino does.
            runCatching { port.dtr = true }
            runCatching { port.rts = true }
        } catch (e: Exception) {
            runCatching { port.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        val input = object : InputStream() {
            private val one = ByteArray(1)
            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                // A timeout of 0 waits until something comes; closing the port ends the wait.
                val buf = if (off == 0) b else ByteArray(len)
                val n = port.read(buf, len, 0)
                if (off != 0 && n > 0) System.arraycopy(buf, 0, b, off, n)
                return n
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                port.write(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len), 2000)
            }
        }
        return Closeable { runCatching { port.close() } } to StreamSession.Streams(input, output)
    }

    private fun askPermission(context: Context, um: UsbManager, device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
            um.requestPermission(device, pi)
            latch.await(2, TimeUnit.MINUTES)
        } finally {
            context.unregisterReceiver(receiver)
        }
        return granted
    }
}

/** Bluetooth serial (SPP) devices, paired in the phone's settings first. */
object Bluetooth {
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    class Paired(val address: String, val name: String)

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<Paired> {
        if (!allowed(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map { Paired(it.address, it.name ?: it.address) }.sortedBy { it.name.lowercase() }
    }

    @SuppressLint("MissingPermission")
    fun open(context: Context, address: String, show: (String) -> Unit): Pair<Closeable, StreamSession.Streams> {
        if (!allowed(context)) throw IOException("Allow Nearby devices for Whelk in the phone's app settings")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("No Bluetooth on this device")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        if (address.isEmpty()) throw IOException("Pick a device in the connection's settings")
        val device = adapter.getRemoteDevice(address)
        show("Connecting to ${device.name ?: address}…")
        val socket = connect(listOf(
            { device.createRfcommSocketToServiceRecord(SPP) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            // Some modules only answer on channel 1, which needs the hidden method.
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
        ))
        return socket to StreamSession.Streams(socket.inputStream, socket.outputStream)
    }

    private fun connect(ways: List<() -> BluetoothSocket>): BluetoothSocket {
        var last: Exception? = null
        for (way in ways) {
            val s = try { way() } catch (e: Exception) { last = e; continue }
            try {
                s.connect()
                return s
            } catch (e: Exception) {
                last = e
                runCatching { s.close() }
            }
        }
        throw IOException("Couldn't connect: ${last?.message ?: "no answer"}")
    }
}
