package org.example.khgprint

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal IPP/1.1 client for a single, fixed KHG CUPS printer queue.
 * This is a diagnostic prototype, not a general-purpose driver or IPP library.
 * Credentials remain only in memory while a request is made.
 */
class IppClient {
    companion object {
        private const val ENDPOINT = "https://davinci.khg.jku.at:631/printers/P3055"
        private const val PRINTER_URI = "ipps://davinci.khg.jku.at:631/printers/P3055"
    }

    data class IppReply(val status: Int, val attributes: Map<String, List<String>>) {
        fun values(name: String): String = attributes[name]?.joinToString(", ") ?: "not provided"
        fun isSuccess(): Boolean = status in 0x0000..0x00ff
        fun isOptionalAttributesError(): Boolean = status == 0x0400 || status == 0x040b
        fun summary(): String {
            val message = attributes["status-message"]?.firstOrNull()
            return "IPP status: 0x%04x".format(status) +
                if (message.isNullOrBlank()) "" else " · $message"
        }
    }

    fun check(username: String, password: String): IppReply {
        val request = makeRequest(0x000B, username) { out ->
            addAttribute(out, 0x44, "requested-attributes", "printer-state")
            addAttribute(out, 0x44, "", "printer-is-accepting-jobs")
            addAttribute(out, 0x44, "", "document-format-supported")
            addAttribute(out, 0x44, "", "uri-authentication-supported")
            addAttribute(out, 0x44, "", "uri-security-supported")
            addAttribute(out, 0x44, "", "printer-name")
        }
        return post(username, password, request, null)
    }

    fun printPdf(
        username: String,
        password: String,
        resolver: ContentResolver,
        pdfUri: Uri,
        pageRange: String = "All pages",
        copies: Int = 1,
        duplex: Boolean = false,
        colorMode: String = "Color"
    ): IppReply {
        val request = makeRequest(0x0002, username) { out ->
            addAttribute(out, 0x42, "job-name", "KHG Android PDF")
            addAttribute(out, 0x49, "document-format", "application/pdf")
            addAttribute(out, 0x21, "copies", copies.toString())
            addAttribute(out, 0x44, "sides", if (duplex) "two-sided-long-edge" else "one-sided")
            addAttribute(out, 0x44, "print-color-mode", if (colorMode == "Color") "color" else "monochrome")
            if (pageRange != "All pages") addPageRanges(out, pageRange)
        }
        val result = submitPdf(resolver, pdfUri, username, password, request)
        if (!result.isOptionalAttributesError()) return result

        // Older KHG/CUPS queues may reject optional print attributes. Retry the
        // original minimal Print-Job request so printing still works while the
        // editor remains available for queues that support those attributes.
        val compatibilityRequest = makeRequest(0x0002, username) { out ->
            addAttribute(out, 0x42, "job-name", "KHG Android PDF")
            addAttribute(out, 0x49, "document-format", "application/pdf")
        }
        return submitPdf(resolver, pdfUri, username, password, compatibilityRequest)
    }

    private fun submitPdf(
        resolver: ContentResolver,
        pdfUri: Uri,
        username: String,
        password: String,
        request: ByteArray
    ): IppReply {
        val pdf = resolver.openInputStream(pdfUri) ?: error("Cannot open selected PDF")
        return pdf.use { post(username, password, request, it) }
    }

    private fun addPageRanges(out: DataOutputStream, value: String) {
        val ranges = value.split(',').map { token ->
            val parts = token.trim().split('-', limit = 2)
            require(parts.size <= 2 && parts.all { it.toIntOrNull() != null }) {
                "Invalid page range: $token"
            }
            val start = parts[0].toInt()
            val end = (parts.getOrNull(1)?.toInt() ?: start)
            require(start > 0 && end >= start) { "Invalid page range: $token" }
            start to end
        }
        ranges.forEachIndexed { index, (start, end) ->
            val name = if (index == 0) "page-ranges" else ""
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(0x33) // rangeOfInteger
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeInt(start)
            out.writeInt(end)
        }
    }


    private fun makeRequest(
        operation: Int,
        username: String,
        extra: (DataOutputStream) -> Unit
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeByte(1) // IPP major version
        out.writeByte(1) // IPP minor version
        out.writeShort(operation)
        out.writeInt(1) // Request ID; fixed for one operation at a time in this prototype
        out.writeByte(0x01) // operation-attributes-tag
        addAttribute(out, 0x47, "attributes-charset", "utf-8")
        addAttribute(out, 0x48, "attributes-natural-language", "en")
        addAttribute(out, 0x45, "printer-uri", PRINTER_URI)
        addAttribute(out, 0x42, "requesting-user-name", username)
        extra(out)
        out.writeByte(0x03) // end-of-attributes-tag
        out.flush()
        return buffer.toByteArray()
    }

    private fun addAttribute(out: DataOutputStream, tag: Int, name: String, value: String) {
        val n = name.toByteArray(Charsets.UTF_8)
        val v = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(n.size)
        out.write(n)
        out.writeShort(v.size)
        out.write(v)
    }

    private fun post(username: String, password: String, ippHeader: ByteArray, pdf: InputStream?): IppReply {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 30000
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/ipp")
            setRequestProperty("Accept", "application/ipp")
            val creds = "$username:$password".toByteArray(Charsets.UTF_8)
            setRequestProperty(
                "Authorization",
                "Basic " + Base64.encodeToString(creds, Base64.NO_WRAP)
            )
            creds.fill(0)
            if (pdf == null) setFixedLengthStreamingMode(ippHeader.size)
            else setChunkedStreamingMode(8192)
        }
        try {
            connection.outputStream.use { output ->
                output.write(ippHeader)
                if (pdf != null) pdf.copyTo(output, bufferSize = 8192)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val challenge = connection.getHeaderField("WWW-Authenticate")
                val extra = if (code == 401) {
                    "Authentication required. Server challenge: ${challenge ?: "not provided"}. " +
                    "This prototype currently implements HTTP Basic only."
                } else {
                    "Server returned HTTP $code (${connection.responseMessage})."
                }
                error(extra)
            }
            val mime = connection.contentType.orEmpty().lowercase()
            if (!mime.startsWith("application/ipp")) {
                error("Server returned $mime instead of an IPP response")
            }
            val response = connection.inputStream.use { it.readBytes() }
            return parseResponse(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(bytes: ByteArray): IppReply {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(bytes.size >= 8) { "Invalid/empty IPP response" }
        input.readUnsignedByte() // IPP version major
        input.readUnsignedByte() // IPP version minor
        val status = input.readUnsignedShort()
        input.readInt() // request-id
        val attrs = linkedMapOf<String, MutableList<String>>()
        var lastName = ""
        while (input.available() > 0) {
            val tag = input.readUnsignedByte()
            if (tag == 0x03) break
            if (tag in 0x01..0x09) continue // group delimiter
            val nameSize = input.readUnsignedShort()
            val nameBytes = ByteArray(nameSize)
            input.readFully(nameBytes)
            if (nameSize > 0) lastName = String(nameBytes, Charsets.UTF_8)
            val valueSize = input.readUnsignedShort()
            val value = ByteArray(valueSize)
            input.readFully(value)
            if (lastName.isNotEmpty()) {
                val decoded = when {
                    tag == 0x22 && value.size == 1 -> (value[0].toInt() != 0).toString()
                    (tag == 0x21 || tag == 0x23) && value.size == 4 ->
                        java.nio.ByteBuffer.wrap(value).int.toString()
                    tag in 0x40..0x5f -> String(value, Charsets.UTF_8)
                    else -> "[${value.size} bytes]"
                }
                attrs.getOrPut(lastName) { mutableListOf() }.add(decoded)
            }
        }
        return IppReply(status, attrs)
    }
}
