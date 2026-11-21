package com.local.assistant.llm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sections of a LiteRT-LM bundle, read from its header, so [ModelFormat] can tell an
 * EmbeddingGemma 2 file from another kind before the runtime is given it.
 *
 * File layout (LiteRT-LM `schema/core`): "LITERTLM", the version at 8, the header's end offset
 * (uint64) at 24, and from 32 a FlatBuffer `LiteRTLMMetaData` describing 16 KB-aligned sections.
 */
object ModelSections {

    /**
     * The `model_type` of each of [file]'s sections: "tf_lite_prefill_decode" for a chat
     * model's text decoder, "tf_lite_text_encoder" for an embedding model's encoder, and so on.
     * Null when the header can't be read.
     */
    fun sectionTypes(file: File): Set<String>? = runCatching {
        RandomAccessFile(file, "r").use { raf -> header(raf)?.let { Header(it).modelTypes() } }
    }.getOrNull()

    /** The FlatBuffer header's bytes, or null when this isn't a LiteRT-LM file. */
    private fun header(raf: RandomAccessFile): ByteArray? {
        if (raf.length() < HEADER_BEGIN) return null
        val start = ByteArray(HEADER_BEGIN)
        raf.seek(0)
        raf.readFully(start)
        if (String(start, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
        val end = ByteBuffer.wrap(start, HEADER_END_AT, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (end <= HEADER_BEGIN || end > minOf(raf.length(), MAX_HEADER.toLong())) return null
        return ByteArray((end - HEADER_BEGIN).toInt()).also(raf::readFully)
    }

    /** Just enough FlatBuffer reading for `LiteRTLMMetaData` (litertlm_header_schema.fbs). */
    private class Header(bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        /** Every section's `model_type` string. */
        fun modelTypes(): Set<String> {
            val sections = table(indirect(0), ROOT_SECTION_METADATA) ?: return emptySet()
            return vector(sections, SECTIONS_OBJECTS).mapNotNull { section ->
                vector(section, SECTION_ITEMS).firstOrNull { key(it) == MODEL_TYPE }?.let { stringValue(it)?.first }
            }.toSet()
        }

        private fun u32(at: Int) = buf.getInt(at)
        private fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF
        private fun indirect(at: Int) = at + u32(at)

        /** Where a table's field is, or null when absent. */
        private fun field(table: Int, index: Int): Int? {
            val vtable = table - u32(table)
            val slot = 4 + 2 * index
            if (slot >= u16(vtable)) return null
            val offset = u16(vtable + slot)
            return if (offset == 0) null else table + offset
        }

        private fun table(table: Int, index: Int): Int? = field(table, index)?.let(::indirect)

        private fun vector(table: Int, index: Int): List<Int> {
            val at = field(table, index)?.let(::indirect) ?: return emptyList()
            return (0 until u32(at)).map { indirect(at + 4 + 4 * it) }
        }

        private fun string(at: Int): Pair<String, Int> {
            val length = u32(at)
            return String(buf.array(), at + 4, length, Charsets.UTF_8) to at + 4
        }

        private fun key(item: Int): String? = field(item, KV_KEY)?.let { string(indirect(it)).first }

        /** A KeyValuePair's value when it is a StringValue. */
        private fun stringValue(item: Int): Pair<String, Int>? {
            val type = field(item, KV_VALUE_TYPE)?.let { buf.get(it).toInt() } ?: return null
            if (type != VDATA_STRING_VALUE) return null
            val value = table(item, KV_VALUE) ?: return null
            return field(value, 0)?.let { string(indirect(it)) }
        }
    }

    private const val MAGIC = "LITERTLM"
    private const val HEADER_BEGIN = 32
    private const val HEADER_END_AT = 24

    /** Sections start at 16 KB boundaries; a header is never near this. */
    private const val MAX_HEADER = 1 shl 20

    private const val MODEL_TYPE = "model_type"

    // Field indices, in schema order.
    private const val ROOT_SECTION_METADATA = 1
    private const val SECTIONS_OBJECTS = 0
    private const val SECTION_ITEMS = 0
    private const val KV_KEY = 0
    private const val KV_VALUE_TYPE = 1
    private const val KV_VALUE = 2

    /** `VData` union: NONE, UInt8, Int8, UInt16, Int16, UInt32, Int32, Float32, Bool, StringValue… */
    private const val VDATA_STRING_VALUE = 9
}
