package com.medtroniclabs.microcoaching.ai.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * EmbeddingGemma's SentencePiece vocabulary: the piece → id map, each piece's merge
 * score, and the special ids the encoder brackets its input with.
 *
 * Loaded either from the model's own protobuf ([fromModelProto], production) or from a
 * trimmed JSON subset ([fromTrimmedJson], tests — the real file is a gated 4.5 MB
 * Gemma artifact that cannot be committed).
 *
 * The model this serves declares `model_type: BPE`, `normalizer: identity` with an
 * empty charsmap, `escape_whitespaces: true`, `add_dummy_prefix: false`,
 * `remove_extra_whitespaces: false` and `byte_fallback: true`. Those five settings are
 * the whole of the text preprocessing, which is why [SentencePieceBpeTokenizer] needs
 * no Unicode normalization tables.
 */
internal class SentencePieceVocab(
    /** Every id in the real vocabulary, including ones this instance does not carry. */
    val vocabSize: Int,
    val bosId: Int,
    val eosId: Int,
    val padId: Int,
    val unkId: Int,
    private val idByPiece: Map<String, Int>,
    private val scoreByPiece: Map<String, Float>,
    /** Pieces the prefix matcher takes whole; BPE never merges across or into them. */
    private val userDefined: Set<String>,
    /** `<0xNN>` piece id per byte value — the byte-fallback target. */
    private val byteIds: IntArray,
) {
    /** Longest user-defined piece, bounding the prefix scan. */
    private val longestUserDefined: Int = userDefined.maxOfOrNull { it.length } ?: 0

    private val byteIdSet: Set<Int> = byteIds.filter { it >= 0 }.toSet()

    fun idOf(piece: String): Int? = idByPiece[piece]

    fun scoreOf(piece: String): Float? = scoreByPiece[piece]

    fun isByteId(id: Int): Boolean = id in byteIdSet

    fun byteId(value: Int): Int = byteIds[value and 0xFF]

    /**
     * The longest user-defined piece starting at [from], or null. Control and special
     * tokens are matched here so a literal "\n" or a run of spaces reaches the model as
     * the single token it was trained with rather than as merged ordinary pieces.
     */
    fun matchUserDefined(text: String, from: Int): String? {
        val limit = minOf(text.length, from + longestUserDefined)
        for (end in limit downTo from + 1) {
            val candidate = text.substring(from, end)
            if (candidate in userDefined) return candidate
        }
        return null
    }

    companion object {

        /** SentencePiece piece types, from `sentencepiece_model.proto`. */
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_CONTROL = 3
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_BYTE = 6

        /**
         * Parse the pieces out of a `sentencepiece.model` protobuf.
         *
         * Only `ModelProto.pieces` (field 1) and its `piece`/`score`/`type` fields are
         * read — the trainer and normalizer specs are already known constants for this
         * model, verified by the fixture generator, so a full proto runtime would buy
         * nothing but a dependency.
         */
        fun fromModelProto(
            bytes: ByteArray,
            vocabSize: Int? = null,
            bosId: Int = 2,
            eosId: Int = 1,
            padId: Int = 0,
            unkId: Int = 3,
        ): SentencePieceVocab {
            val pieces = ArrayList<Triple<String, Float, Int>>(262_144)
            val reader = ProtoReader(bytes)
            while (reader.hasMore()) {
                val (field, wire) = reader.readTag()
                if (field == 1 && wire == ProtoReader.WIRE_LENGTH_DELIMITED) {
                    pieces += readPiece(reader.readLengthDelimited())
                } else {
                    reader.skip(wire)
                }
            }
            return build(pieces, vocabSize ?: pieces.size, bosId, eosId, padId, unkId)
        }

        private fun readPiece(bytes: ByteArray): Triple<String, Float, Int> {
            var piece = ""
            var score = 0f
            var type = 1
            val reader = ProtoReader(bytes)
            while (reader.hasMore()) {
                val (field, wire) = reader.readTag()
                when {
                    field == 1 && wire == ProtoReader.WIRE_LENGTH_DELIMITED ->
                        piece = reader.readLengthDelimited().toString(Charsets.UTF_8)
                    field == 2 && wire == ProtoReader.WIRE_FIXED32 ->
                        score = Float.fromBits(reader.readFixed32())
                    field == 3 && wire == ProtoReader.WIRE_VARINT ->
                        type = reader.readVarint().toInt()
                    else -> reader.skip(wire)
                }
            }
            return Triple(piece, score, type)
        }

        /** Reads the trimmed CI subset: `{vocab_size, bos_id, …, pieces: [[id, piece, score, type], …]}`. */
        fun fromTrimmedJson(text: String): SentencePieceVocab {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonObject
            val idByPiece = HashMap<String, Int>()
            val scoreByPiece = HashMap<String, Float>()
            val userDefined = HashSet<String>()
            val byteIds = IntArray(256) { -1 }
            for (row in root.getValue("pieces").jsonArray) {
                val cells = row.jsonArray
                val id = cells[0].jsonPrimitive.int
                val piece = cells[1].jsonPrimitive.content
                val score = cells[2].jsonPrimitive.float
                val type = cells[3].jsonPrimitive.int
                register(piece, id, score, type, idByPiece, scoreByPiece, userDefined, byteIds)
            }
            return SentencePieceVocab(
                vocabSize = root.getValue("vocab_size").jsonPrimitive.int,
                bosId = root.getValue("bos_id").jsonPrimitive.int,
                eosId = root.getValue("eos_id").jsonPrimitive.int,
                padId = root.getValue("pad_id").jsonPrimitive.int,
                unkId = root.getValue("unk_id").jsonPrimitive.int,
                idByPiece = idByPiece,
                scoreByPiece = scoreByPiece,
                userDefined = userDefined,
                byteIds = byteIds,
            )
        }

        private fun build(
            pieces: List<Triple<String, Float, Int>>,
            vocabSize: Int,
            bosId: Int,
            eosId: Int,
            padId: Int,
            unkId: Int,
        ): SentencePieceVocab {
            val idByPiece = HashMap<String, Int>(pieces.size * 2)
            val scoreByPiece = HashMap<String, Float>(pieces.size * 2)
            val userDefined = HashSet<String>()
            val byteIds = IntArray(256) { -1 }
            pieces.forEachIndexed { id, (piece, score, type) ->
                register(piece, id, score, type, idByPiece, scoreByPiece, userDefined, byteIds)
            }
            return SentencePieceVocab(
                vocabSize, bosId, eosId, padId, unkId, idByPiece, scoreByPiece, userDefined, byteIds,
            )
        }

        private fun register(
            piece: String,
            id: Int,
            score: Float,
            type: Int,
            idByPiece: MutableMap<String, Int>,
            scoreByPiece: MutableMap<String, Float>,
            userDefined: MutableSet<String>,
            byteIds: IntArray,
        ) {
            if (piece.isEmpty()) return
            when (type) {
                TYPE_BYTE -> {
                    byteValueOf(piece)?.let { byteIds[it] = id }
                    // Byte pieces are reachable only through fallback, never by matching
                    // the literal text "<0x41>", so they stay out of the piece map.
                    return
                }
                TYPE_UNKNOWN -> return
                TYPE_CONTROL, TYPE_USER_DEFINED -> userDefined += piece
            }
            idByPiece[piece] = id
            scoreByPiece[piece] = score
        }

        /** `<0x1F>` → 31. */
        private fun byteValueOf(piece: String): Int? {
            if (piece.length != 6 || !piece.startsWith("<0x") || !piece.endsWith(">")) return null
            return piece.substring(3, 5).toIntOrNull(16)
        }
    }
}

/** Minimal protobuf wire reader — enough for `ModelProto.pieces`. */
private class ProtoReader(private val bytes: ByteArray) {
    private var pos = 0

    fun hasMore(): Boolean = pos < bytes.size

    fun readTag(): Pair<Int, Int> {
        val key = readVarint().toInt()
        return (key ushr 3) to (key and 0x7)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readFixed32(): Int {
        val v = (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readLengthDelimited(): ByteArray {
        val len = readVarint().toInt()
        val out = bytes.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun skip(wire: Int) {
        when (wire) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> pos += 8
            WIRE_LENGTH_DELIMITED -> {
                // The length must land in a local before the seek: `pos += readVarint()`
                // reads pos first and then discards the bytes the varint itself consumed.
                val len = readVarint().toInt()
                pos += len
            }
            WIRE_FIXED32 -> pos += 4
            else -> error("unsupported protobuf wire type $wire")
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5
    }
}
