/** Pinoy Diner Epson Print Agent | v1.2.0 | 2026-08-24 */
package com.pinoydiner.printagent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

object EscPosPrinter {
    private val charset: Charset = Charset.forName("windows-1252")

    data class Result(val success: Boolean, val message: String)

    fun printOrder(host: String, port: Int, json: String): Result {
        return try {
            val order = JSONObject(json)
            send(host, port, buildOrderReceipt(order))
            Result(true, "Printed to $host:$port")
        } catch (e: Exception) {
            Result(false, e.message ?: "Printer connection failed")
        }
    }

    fun printTest(host: String, port: Int): Result {
        return try {
            val out = ByteArrayOutputStream()
            init(out)
            center(out)
            bold(out, true)
            size(out, 2, 2)
            text(out, "PINOY DINER\n")
            size(out, 1, 1)
            text(out, "EPSON PRINTER TEST\n")
            bold(out, false)
            text(out, "------------------------------\n")
            left(out)
            text(out, "Printer: $host:$port\n")
            text(out, "RAW TCP / ESC-POS\n")
            text(out, "If you can read this, printing is ready.\n")
            text(out, "------------------------------\n\n\n")
            cut(out)
            send(host, port, out.toByteArray())
            Result(true, "Test receipt printed")
        } catch (e: Exception) {
            Result(false, e.message ?: "Printer connection failed")
        }
    }

    private fun buildOrderReceipt(order: JSONObject): ByteArray {
        val out = ByteArrayOutputStream()
        val name = order.optString("name", "")
        val mobile = order.optString("mobile", "")
        val date = order.optString("date", "")
        val time = order.optString("time", "")
        val items = order.optString("orders", "")
        val sourceRow = order.optInt("sourceRow", 0)
        val printCount = order.optInt("printCount", 0)
        val isReprint = printCount > 0

        init(out)
        center(out)
        bold(out, true)
        size(out, 2, 2)
        text(out, "PINOY DINER\n")
        size(out, 1, 1)
        text(out, if (isReprint) "*** REPRINT ***\n" else "NEW ORDER\n")
        bold(out, false)
        text(out, "==========================================\n")

        bold(out, true)
        size(out, 2, 2)
        text(out, "PICKUP\n${time.ifBlank { "-" }}\n")
        size(out, 1, 1)
        bold(out, false)
        text(out, "==========================================\n")

        left(out)
        bold(out, true)
        text(out, "CUSTOMER\n")
        bold(out, false)
        wrap(out, name.ifBlank { "-" }, 42)
        if (mobile.isNotBlank()) wrap(out, mobile, 42)
        text(out, "\n")

        bold(out, true)
        text(out, "DATE\n")
        bold(out, false)
        wrap(out, date.ifBlank { "-" }, 42)
        text(out, "\n------------------------------------------\n")

        bold(out, true)
        size(out, 1, 2)
        text(out, "ORDER\n")
        size(out, 1, 1)
        bold(out, false)
        text(out, "------------------------------------------\n")
        items.replace("\r", "").split("\n").forEach { line ->
            if (line.isBlank()) text(out, "\n") else wrap(out, line, 42)
        }
        text(out, "------------------------------------------\n")
        if (sourceRow > 0) text(out, "Sheet row: $sourceRow\n")
        if (isReprint) {
            bold(out, true)
            center(out)
            text(out, "*** REPRINT - CHECK BEFORE COOKING ***\n")
            bold(out, false)
        }
        text(out, "\n\n\n")
        cut(out)
        return out.toByteArray()
    }

    private fun send(host: String, port: Int, bytes: ByteArray) {
        require(host.isNotBlank()) { "Enter the Epson printer IP address" }
        require(port in 1..65535) { "Invalid printer port" }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.trim(), port), 5000)
            socket.soTimeout = 8000
            socket.getOutputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
        }
    }

    private fun init(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1B, 0x40))
    private fun left(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1B, 0x61, 0x00))
    private fun center(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1B, 0x61, 0x01))
    private fun bold(out: ByteArrayOutputStream, on: Boolean) = out.write(byteArrayOf(0x1B, 0x45, if (on) 0x01 else 0x00))
    private fun size(out: ByteArrayOutputStream, width: Int, height: Int) {
        val w = (width.coerceIn(1, 8) - 1) shl 4
        val h = height.coerceIn(1, 8) - 1
        out.write(byteArrayOf(0x1D, 0x21, (w or h).toByte()))
    }
    private fun cut(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1D, 0x56, 0x00))
    private fun text(out: ByteArrayOutputStream, value: String) = out.write(value.toByteArray(charset))

    private fun wrap(out: ByteArrayOutputStream, value: String, width: Int) {
        var remaining = value.trim()
        if (remaining.isEmpty()) { text(out, "\n"); return }
        while (remaining.length > width) {
            var split = remaining.lastIndexOf(' ', width)
            if (split <= 0) split = width
            text(out, remaining.substring(0, split).trimEnd() + "\n")
            remaining = remaining.substring(split).trimStart()
        }
        text(out, remaining + "\n")
    }
}
