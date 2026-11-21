package org.fossify.documents.data

import android.content.ContentResolver
import android.net.Uri
import org.fossify.documents.models.DocumentKind
import org.zwobble.mammoth.DocumentConverter
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

internal class StructuredDocumentLoader(
    private val resolver: ContentResolver,
) {
    fun load(uri: Uri, kind: DocumentKind, fileName: String): StructuredDocumentLoadResult {
        return when (kind) {
            DocumentKind.DOCX -> loadDocx(uri)
            DocumentKind.CSV -> loadCsv(uri, fileName)
            DocumentKind.HTML -> loadHtml(uri)
            else -> error("Unsupported structured document type: $kind")
        }
    }

    private fun loadDocx(uri: Uri): StructuredDocumentLoadResult {
        openInput(uri).use(::validateDocxXml)
        val html = openInput(uri).use { input ->
            DocumentConverter().convertToHtml(input).value
        }
        val embeddedFont = extractDocxEmbeddedFont(uri)
        return StructuredDocumentLoadResult(
            content = StructuredDocumentContent.Web(
                html = HtmlDocumentSanitizer.sanitize(html, isFragment = true),
                embeddedFont = embeddedFont,
            ),
        )
    }

    private fun extractDocxEmbeddedFont(uri: Uri): EmbeddedDocFont? {
        return runCatching {
            var fontBytes: ByteArray? = null
            var fontEntryName: String? = null
            var fontTableXml: String? = null

            openInput(uri).use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val lowerName = entry.name.lowercase()
                        if (lowerName.startsWith("word/fonts/") &&
                            (lowerName.endsWith(".ttf") || lowerName.endsWith(".otf") || lowerName.endsWith(".odttf"))
                        ) {
                            fontBytes = zip.readBytes()
                            fontEntryName = entry.name
                        } else if (lowerName.endsWith("word/fonttable.xml")) {
                            fontTableXml = zip.reader(Charsets.UTF_8).readText()
                        }
                        zip.closeEntry()
                    }
                }
            }

            val rawBytes = fontBytes ?: return null
            if (rawBytes.isEmpty()) return null

            val entryName = fontEntryName.orEmpty()
            val fontNameMatch = fontTableXml?.let {
                Regex("""<w:font\s+[^>]*w:name="([^"]+)"""").find(it)?.groupValues?.get(1)
            }
            val familyName = fontNameMatch ?: "EmbeddedDocxFont"

            if (entryName.endsWith(".odttf", ignoreCase = true)) {
                val guidMatch = fontTableXml?.let {
                    Regex("""w:fontKey="\{?([0-9a-fA-F\-]+)\}?"""").find(it)?.groupValues?.get(1)
                } ?: entryName.substringAfterLast('/').substringBeforeLast('.')
                val deobfuscated = deobfuscateOdttf(rawBytes, guidMatch)
                EmbeddedDocFont(
                    bytes = deobfuscated,
                    mimeType = "font/ttf",
                    format = "truetype",
                    familyName = familyName,
                )
            } else if (entryName.endsWith(".otf", ignoreCase = true)) {
                EmbeddedDocFont(
                    bytes = rawBytes,
                    mimeType = "font/otf",
                    format = "opentype",
                    familyName = familyName,
                )
            } else {
                EmbeddedDocFont(
                    bytes = rawBytes,
                    mimeType = "font/ttf",
                    format = "truetype",
                    familyName = familyName,
                )
            }
        }.getOrNull()
    }

    private fun deobfuscateOdttf(bytes: ByteArray, guidStr: String): ByteArray {
        val hex = guidStr.replace("{", "").replace("}", "").replace("-", "")
        if (hex.length != 32) return bytes
        val rawKey = ByteArray(16) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val guidKey = ByteArray(16)
        guidKey[0] = rawKey[3]; guidKey[1] = rawKey[2]; guidKey[2] = rawKey[1]; guidKey[3] = rawKey[0]
        guidKey[4] = rawKey[5]; guidKey[5] = rawKey[4]
        guidKey[6] = rawKey[7]; guidKey[7] = rawKey[6]
        System.arraycopy(rawKey, 8, guidKey, 8, 8)

        val out = bytes.copyOf()
        val limit = minOf(32, out.size)
        for (i in 0 until limit) {
            out[i] = (out[i].toInt() xor guidKey[i % 16].toInt()).toByte()
        }
        return out
    }

    private fun loadCsv(uri: Uri, fileName: String): StructuredDocumentLoadResult {
        val decoded = readText(uri)
        return StructuredDocumentLoadResult(
            content = CsvDocumentParser.parse(decoded.text, fileName),
            canEditText = decoded.encoding != null,
        )
    }

    private fun loadHtml(uri: Uri): StructuredDocumentLoadResult {
        val decoded = readText(uri)
        return StructuredDocumentLoadResult(
            content = StructuredDocumentContent.Web(
                html = HtmlDocumentSanitizer.sanitize(decoded.text),
            ),
        )
    }

    private fun readText(uri: Uri): DecodedTextDocument {
        return openInput(uri).use { input -> TextDocumentCodec.decode(input.readBytes()) }
    }

    private fun openInput(uri: Uri): InputStream {
        return resolver.openInputStream(uri) ?: throw IOException("Could not open this document.")
    }

}

internal data class StructuredDocumentLoadResult(
    val content: StructuredDocumentContent,
    val canEditText: Boolean = false,
)

internal fun validateDocxXml(input: InputStream) {
    validateDocxXml(input, MAX_DOCX_ENTRIES, MAX_DOCX_UNCOMPRESSED_BYTES)
}

internal fun validateDocxXml(
    input: InputStream,
    maxEntries: Int,
    maxUncompressedBytes: Long,
) {
    ZipInputStream(input.buffered()).use { zip ->
        var entryCount = 0
        var uncompressedBytes = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount += 1
            if (entryCount > maxEntries) {
                throw DocumentTooLargeException()
            }

            val entryName = entry.name.lowercase()
            val scanXml = entryName.endsWith(".xml") || entryName.endsWith(".rels")
            uncompressedBytes += zip.validateDocxEntry(
                scanXml = scanXml,
                remainingBytes = maxUncompressedBytes - uncompressedBytes,
            )
            zip.closeEntry()
        }
    }
}

private fun InputStream.validateDocxEntry(
    scanXml: Boolean,
    remainingBytes: Long,
): Long {
    val matched = IntArray(DOCTYPE_MARKERS.size)
    val buffer = ByteArray(XML_SCAN_BUFFER_SIZE)
    var bytesRead = 0L
    while (true) {
        val count = read(buffer)
        if (count == -1) {
            return bytesRead
        }

        bytesRead += count
        if (bytesRead > remainingBytes) {
            throw DocumentTooLargeException()
        }
        if (!scanXml) {
            continue
        }

        repeat(count) { index ->
            val value = buffer[index].toInt().and(UNSIGNED_BYTE_MASK).uppercaseAscii()
            DOCTYPE_MARKERS.forEachIndexed { markerIndex, marker ->
                matched[markerIndex] = when (value) {
                    marker[matched[markerIndex]].toInt().and(UNSIGNED_BYTE_MASK) -> {
                        matched[markerIndex] + MATCH_INCREMENT
                    }

                    marker.first().toInt().and(UNSIGNED_BYTE_MASK) -> MATCH_INCREMENT
                    else -> 0
                }
                if (matched[markerIndex] == marker.size) {
                    throw IOException("This document contains an unsupported XML declaration.")
                }
            }
        }
    }
}

internal class DocumentTooLargeException : IOException()

private fun Int.uppercaseAscii(): Int = if (this in 'a'.code..'z'.code) this - ASCII_CASE_OFFSET else this

private fun String.utf16Marker(littleEndian: Boolean): ByteArray {
    return ByteArray(length * 2) { index ->
        val character = this[index / 2].code.toByte()
        if ((index % 2 == 0) == littleEndian) character else 0
    }
}

private const val DOCTYPE_MARKER = "<!DOCTYPE"
private const val XML_SCAN_BUFFER_SIZE = 8_192
private const val ASCII_CASE_OFFSET = 32
private const val UNSIGNED_BYTE_MASK = 0xFF
private const val MATCH_INCREMENT = 1
private const val MAX_DOCX_ENTRIES = 4_096
private const val MAX_DOCX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
private val DOCTYPE_MARKERS = listOf(
    DOCTYPE_MARKER.encodeToByteArray(),
    DOCTYPE_MARKER.utf16Marker(littleEndian = true),
    DOCTYPE_MARKER.utf16Marker(littleEndian = false),
)
