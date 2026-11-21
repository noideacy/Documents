package org.fossify.documents.data

internal sealed interface StructuredDocumentContent {
    data class Web(
        val html: String,
        val embeddedFont: EmbeddedDocFont? = null,
    ) : StructuredDocumentContent

    data class Table(
        val rows: List<List<String>>,
        val columnCount: Int,
    ) : StructuredDocumentContent
}

internal data class EmbeddedDocFont(
    val bytes: ByteArray,
    val mimeType: String,
    val format: String,
    val familyName: String = "EmbeddedDocxFont",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedDocFont) return false
        return bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType &&
            format == other.format &&
            familyName == other.familyName
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + familyName.hashCode()
        return result
    }
}
