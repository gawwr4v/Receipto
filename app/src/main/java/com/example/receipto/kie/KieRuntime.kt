package com.example.receipto.kie

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.google.gson.Gson
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

data class KieConfig(
    val max_tokens: Int,
    val max_chars: Int,
    val pad_id: Int = 0,
    val unk_id: Int = 1
)

data class OcrToken(
    val text: String,
    val box: Rect
)

data class EncodedInputs(
    val charIds: IntArray, // [T*C]
    val bbox: FloatArray, // [T*4] in [0,1] xyxy
    val regionIds: IntArray // [T]
)

class KieRuntime(
    private val context: Context
) {
    private val gson = Gson()
    private val modelPath = "models/kie_select.tflite"
    private val vocabPath = "models/char_vocab.json"
    private val tagsPath = "models/tags.json"
    private val configPath = "models/model_config.json"

    private val interpreter: Interpreter by lazy { createInterpreter() }

    private val config: KieConfig by lazy {
        val json = loadJson(configPath, Map::class.java) as Map<*, *>
        KieConfig(
            max_tokens = (json["max_tokens"] as? Number)?.toInt() ?: 512,
            max_chars = (json["max_chars"] as? Number)?.toInt() ?: 24,
            pad_id = (json["pad_id"] as? Number)?.toInt() ?: 0,
            unk_id = (json["unk_id"] as? Number)?.toInt() ?: 1
        )
    }

    private fun dumpInputsJson(
        inCharIds: Array<Array<IntArray>>,
        inBbox: Array<Array<FloatArray>>,
        inRegionIds: Array<IntArray>,
        T: Int,
        C: Int
    ) {
        try {
            val N = T
            val root = org.json.JSONObject()
            root.put("T", T)
            root.put("C", C)

            val chars = org.json.JSONArray()
            val boxes = org.json.JSONArray()
            val regs = org.json.JSONArray()

            val nDump = kotlin.math.min(N, T)
            for (i in 0 until nDump) {
                val chArr = org.json.JSONArray()
                for (j in 0 until C) chArr.put(inCharIds[0][i][j])
                chars.put(chArr)

                val bbArr = org.json.JSONArray()
                for (k in 0 until 4) bbArr.put(inBbox[0][i][k])
                boxes.put(bbArr)

                regs.put(inRegionIds[0][i])
            }
            root.put("char_ids", chars)
            root.put("bbox", boxes)
            root.put("region_ids", regs)

            val f = java.io.File(context.filesDir, "kie_dump.json")
            f.writeText(root.toString())
            android.util.Log.d("KIE_DEBUG", "Wrote dump: ${f.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("KIE_DEBUG", "Dump failed: ${e.message}", e)
        }
    }

    private fun reorderTokensReadingOrder(
        tokens: List<OcrToken>,
        pageHeight: Int
    ): List<OcrToken> {
        if (tokens.isEmpty()) return tokens

        data class Tn(val tok: OcrToken, val yCenter: Float)
        val tn = tokens.map {
            val yc = ((it.box.top + it.box.bottom) * 0.5f) / pageHeight.toFloat()
            Tn(it, yc.coerceIn(0f, 1f))
        }.sortedBy { it.yCenter }

        val lineThresh = 0.020f
        val lines = mutableListOf<MutableList<OcrToken>>()
        var curLine = mutableListOf<Tn>()
        for (t in tn) {
            if (curLine.isEmpty()) {
                curLine.add(t)
            } else {
                val avgY = curLine.map { it.yCenter }.average().toFloat()
                if (kotlin.math.abs(t.yCenter - avgY) <= lineThresh) {
                    curLine.add(t)
                } else {
                    lines.add(curLine.map { it.tok }.toMutableList())
                    curLine.clear()
                    curLine.add(t)
                }
            }
        }
        if (curLine.isNotEmpty()) {
            lines.add(curLine.map { it.tok }.toMutableList())
        }

        lines.forEach { line -> line.sortBy { it.box.left } }

        val ordered = mutableListOf<OcrToken>()
        lines.forEach { ordered.addAll(it) }
        return ordered
    }

    // Strong normalization to reduce unk chars
    private fun normalizeForVocab(s: String): String {
        if (s.isEmpty()) return s
        // 1) NFKC fold (compatibility; converts full-width to ASCII where possible)
        var t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        // 2) Strip diacritics
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

        val sb = StringBuilder(t.length)
        for (ch in t) {
            val c = when (ch) {
                // Quotes
                '’','‘' -> '\''
                '“','”' -> '"'
                // Dashes
                '–','—' -> '-'
                // Bullets / symbols
                '•' -> '*'
                '×' -> 'x'
                // Currency -> normalize to $
                '€','£','¥','₩','₽','₹' -> '$'
                '¢' -> 'c'
                // Non-breaking and thin spaces -> space
                '\u00A0','\u2007','\u2009','\u2002','\u2003','\u2004','\u2005','\u2006','\u2008','\u200A','\u202F' -> ' '
                // Trademark/registered -> space
                '®','™' -> ' '
                else -> ch
            }
            // Keep visible ASCII only
            if (c in ' '..'~') sb.append(c) else sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private val charVocab: Map<String, Int> by lazy {
        val json = loadJson(vocabPath, Map::class.java) as Map<*, *>
        val charToIdMap = json["char_to_id"] as? Map<*, *>
        charToIdMap?.mapNotNull { (k, v) ->
            val key = (k as? String) ?: return@mapNotNull null
            val id: Int? = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
            id?.let { key to it }
        }?.toMap() ?: emptyMap()
    }

    private val idToTag: List<String> by lazy {
        val json = loadJson(tagsPath, Map::class.java) as Map<*, *>
        val idToTagMap = json["id_to_tag"] as? Map<*, *>
        if (idToTagMap != null) {
            val maxId = idToTagMap.keys.mapNotNull { (it as? String)?.toIntOrNull() }.maxOrNull() ?: 0
            val result = MutableList(maxId + 1) { "O" }
            idToTagMap.forEach { (k, v) ->
                val id = (k as? String)?.toIntOrNull() ?: return@forEach
                result[id] = v?.toString() ?: "O"
            }
            result
        } else {
            val tagsList = json["tags"] as? List<*>
            tagsList?.mapNotNull { it?.toString() } ?: listOf("O")
        }
    }

    private fun createInterpreter(): Interpreter {
        val opts = Interpreter.Options()
        try { opts.setNumThreads(1) } catch (_: Throwable) {}
        try {
            val m = opts::class.java.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType)
            m.invoke(opts, java.lang.Boolean.FALSE)
        } catch (_: Throwable) { /* ignore */ }
        return Interpreter(loadModelFile(modelPath), opts)
    }

    private fun loadModelFile(assetPath: String): ByteBuffer {
        context.assets.open(assetPath).use { input ->
            val bytes = input.readBytes()
            return ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply { put(bytes); rewind() }
        }
    }

    private fun <T> loadJson(assetPath: String, cls: Class<T>): T {
        context.assets.open(assetPath).use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                return gson.fromJson(br, cls)
            }
        }
    }

    // Groups tokens into lines by y_center proximity, then sorts tokens left->right within lines.
    private fun groupTokensIntoLines(
        tokens: List<OcrToken>,
        pageHeight: Int
    ): List<MutableList<OcrToken>> {
        data class Tn(val tok: OcrToken, val yCenter: Float)
        if (tokens.isEmpty()) return emptyList()
        val tn = tokens.map {
            val yc = ((it.box.top + it.box.bottom) * 0.5f) / pageHeight.toFloat()
            Tn(it, yc.coerceIn(0f, 1f))
        }.sortedBy { it.yCenter }

        val lineThresh = 0.015f // tune if needed (0.01-0.03 typical)
        val lines = mutableListOf<MutableList<OcrToken>>()
        var cur = mutableListOf<Tn>()
        for (t in tn) {
            if (cur.isEmpty()) {
                cur.add(t)
            } else {
                val avgY = cur.map { it.yCenter }.average().toFloat()
                if (kotlin.math.abs(t.yCenter - avgY) <= lineThresh) {
                    cur.add(t)
                } else {
                    lines.add(cur.map { it.tok }.toMutableList())
                    cur.clear()
                    cur.add(t)
                }
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.map { it.tok }.toMutableList())

        // Sort tokens within each line left->right
        lines.forEach { it.sortBy { tok -> tok.box.left } }
        return lines
    }

    // Returns true if token text is mostly numeric-like (digits/dot/comma/$).
    private fun isNumericLike(s: String): Boolean {
        if (s.isEmpty()) return false
        var numericChars = 0
        var total = 0
        for (ch in s) {
            total++
            if (ch.isDigit() || ch == '.' || ch == ',' || ch == '$') numericChars++
        }
        return numericChars * 2 >= total
    }

    // Merge adjacent tokens within a line when the horizontal gap is small.
    // Uses a larger gap threshold for numeric-like tokens to capture prices.
    private fun mergeLineTokens(
        line: MutableList<OcrToken>,
        pageWidth: Int
    ): MutableList<OcrToken> {
        if (line.isEmpty()) return line
        val merged = mutableListOf<OcrToken>()
        var curText = StringBuilder(line[0].text)
        var curBox = android.graphics.Rect(line[0].box)

        fun extendRect(base: android.graphics.Rect, other: android.graphics.Rect): android.graphics.Rect {
            return android.graphics.Rect(
                kotlin.math.min(base.left, other.left),
                kotlin.math.min(base.top, other.top),
                kotlin.math.max(base.right, other.right),
                kotlin.math.max(base.bottom, other.bottom)
            )
        }

        for (i in 1 until line.size) {
            val nextTok = line[i]
            val prevBox = curBox
            val nextBox = nextTok.box
            val gapPx = nextBox.left - prevBox.right

            val lastText = curText.toString().trim()
            val nextText = nextTok.text.trim()

            val gapFrac = if (isNumericLike(lastText) || isNumericLike(nextText)) 0.06f else 0.025f
            val gapThreshPx = (gapFrac * pageWidth).toInt()

            if (gapPx <= gapThreshPx && gapPx >= -gapThreshPx) {
                curText.append(" ").append(nextTok.text)
                curBox = extendRect(curBox, nextBox)
            } else {
                merged.add(OcrToken(curText.toString(), android.graphics.Rect(curBox)))
                curText = StringBuilder(nextTok.text)
                curBox = android.graphics.Rect(nextBox)
            }
        }
        merged.add(OcrToken(curText.toString(), android.graphics.Rect(curBox)))
        return merged
    }

    // Group->merge->flatten pipeline.
    private fun reorderAndMergeTokens(
        tokens: List<OcrToken>,
        pageWidth: Int,
        pageHeight: Int
    ): List<OcrToken> {
        val lines = groupTokensIntoLines(tokens, pageHeight)
        val mergedLines = lines.map { mergeLineTokens(it, pageWidth) }
        return mergedLines.flatten()
    }

    fun encode(tokens: List<OcrToken>, pageWidth: Int, pageHeight: Int): EncodedInputs {
        // Mirror training (encode_data.py) and adjust tokenization toward labels via merging
        val T = config.max_tokens
        val C = config.max_chars

        // Training uses 0 for both PAD and unknown chars
        val PAD = 0
        val UNK = 0

        val charIds = IntArray(T * C) { PAD }
        val bbox = FloatArray(T * 4) { 0f }
        val regionIds = IntArray(T) { 0 }

        fun clamp01(x: Float) = x.coerceIn(0f, 1f)

        // Merge small-gap tokens within a line to match label-style tokens (words/phrases)
        val merged = reorderAndMergeTokens(tokens, pageWidth, pageHeight)

        // Track unknown characters by actual character (not by id) because PAD==UNK==0
        val unkByChar = mutableMapOf<Char, Int>()
        var unkCount = 0
        var padOnlyTokens = 0

        val n = minOf(merged.size, T)
        for (i in 0 until n) {
            val tok = merged[i]
            val text = normalizeForVocab(tok.text)
            if (text.isBlank()) padOnlyTokens++

            // bbox normalized [0,1]
            val left = clamp01(tok.box.left.toFloat() / pageWidth.coerceAtLeast(1))
            val top = clamp01(tok.box.top.toFloat() / pageHeight.coerceAtLeast(1))
            val right = clamp01(tok.box.right.toFloat() / pageWidth.coerceAtLeast(1))
            val bottom = clamp01(tok.box.bottom.toFloat() / pageHeight.coerceAtLeast(1))

            val bOff = i * 4
            bbox[bOff] = left
            bbox[bOff + 1] = top
            bbox[bOff + 2] = right
            bbox[bOff + 3] = bottom

            // Region bins: 1 if y_center < 0.2; 3 if > 0.8; else 2
            val yCenter = (top + bottom) * 0.5f
            regionIds[i] = when {
                yCenter < 0.2f -> 1
                yCenter > 0.8f -> 3
                else -> 2
            }

            // char ids: unknown=0; pad=0
            val chars = text.toCharArray()
            val take = minOf(chars.size, C)
            for (j in 0 until C) {
                val off = i * C + j
                if (j < take) {
                    val ch = chars[j]
                    val id = charVocab[ch.toString()] ?: UNK
                    // Count unknowns only for actual characters we tried to encode (not padding)
                    if (id == UNK && ch != ' ') {
                        unkByChar[ch] = (unkByChar[ch] ?: 0) + 1
                        unkCount++
                    }
                    charIds[off] = id
                } else {
                    charIds[off] = PAD
                }
            }
        }

        // Debug: UNK/PAD summary with accurate counts when PAD==UNK==0
        run {
            android.util.Log.d(
                "KIE_DEBUG",
                "ENCODE: vocabSize=${charVocab.size}, tokensEncoded=$n, unkChars=$unkCount, padOnlyTokens=$padOnlyTokens"
            )
            if (unkByChar.isNotEmpty()) {
                val top = unkByChar.entries.sortedByDescending { it.value }.take(10)
                android.util.Log.d(
                    "KIE_DEBUG",
                    "Top unknown chars: " + top.joinToString { "'${it.key}'(0x${it.key.code.toString(16)})=${it.value}" }
                )
            }
        }

        return EncodedInputs(charIds, bbox, regionIds)
    }

    fun infer(inputs: EncodedInputs): List<String> {
        val T = config.max_tokens
        val C = config.max_chars

        // Prepare input arrays
        val inCharIds = Array(1) { Array(T) { IntArray(C) } }
        val inBbox = Array(1) { Array(T) { FloatArray(4) } }
        val inRegionIds = Array(1) { IntArray(T) }

        for (i in 0 until T) {
            val cOff = i * C
            for (j in 0 until C) {
                inCharIds[0][i][j] = inputs.charIds.getOrElse(cOff + j) { config.pad_id }
            }
            val bOff = i * 4
            inBbox[0][i][0] = inputs.bbox.getOrElse(bOff) { 0f }
            inBbox[0][i][1] = inputs.bbox.getOrElse(bOff + 1) { 0f }
            inBbox[0][i][2] = inputs.bbox.getOrElse(bOff + 2) { 0f }
            inBbox[0][i][3] = inputs.bbox.getOrElse(bOff + 3) { 0f }
            inRegionIds[0][i] = inputs.regionIds.getOrElse(i) { 0 }
        }

        val numTags = idToTag.size
        val outLogits = Array(1) { Array(T) { FloatArray(numTags) } }

        // Log some inputs
        run {
            val N = min(5, T)
            Log.d("KIE_DEBUG", "=== MODEL INPUTS ===")
            Log.d("KIE_DEBUG", "Config: T=$T, C=$C, numTags=$numTags, pad=${config.pad_id}, unk=${config.unk_id}")
            for (i in 0 until N) {
                val charStr = inCharIds[0][i].take(10).joinToString(",")
                val bboxStr = inBbox[0][i].joinToString(",") { "%.3f".format(it) }
                Log.d("KIE_DEBUG", "  Token $i: charIds=[$charStr...], bbox=[$bboxStr], region=${inRegionIds[0][i]}")
            }
        }

        // Build input array based on actual interpreter input tensor names/shapes
        data class NamedTensor(val index: Int, val name: String?, val shape: IntArray)
        val inputCount = interpreter.inputTensorCount
        val inputsMeta = (0 until inputCount).map { idx ->
            val t = interpreter.getInputTensor(idx)
            val name = try { t.name() } catch (_: Throwable) { null }
            NamedTensor(idx, name, t.shape())
        }

        for (m in inputsMeta) {
            Log.d("KIE_DEBUG", "InputTensor[${m.index}] name=${m.name ?: "(no-name)"} shape=${m.shape.joinToString("x")}")
        }

        fun pickTensor(meta: NamedTensor): Any {
            val name = meta.name?.lowercase()
            when {
                name?.contains("char") == true -> return inCharIds
                name?.contains("bbox") == true || name?.contains("box") == true -> return inBbox
                name?.contains("region") == true -> return inRegionIds
            }
            // Fallback by shape
            val s = meta.shape
            if (s.size == 3) {
                return if (s.last() == C) inCharIds else inBbox
            }
            if (s.size == 2) return inRegionIds
            // Last resort fallback
            return when (meta.index) {
                0 -> inCharIds
                1 -> inBbox
                else -> inRegionIds
            }
        }

        dumpInputsJson(inCharIds, inBbox, inRegionIds, T, C)
        val inputArray = inputsMeta.map { pickTensor(it) }.toTypedArray()
        val outputMap = mutableMapOf<Int, Any>(0 to outLogits)
        interpreter.runForMultipleInputsOutputs(inputArray, outputMap)

        // Log some outputs
        run {
            val N = min(5, T)
            Log.d("KIE_DEBUG", "=== MODEL OUTPUTS ===")
            for (i in 0 until N) {
                val logits = outLogits[0][i]
                val logitStr = logits.take(5).joinToString(",") { "%.3f".format(it) }
                val maxIdx = logits.indices.maxByOrNull { logits[it] } ?: 0
                val maxVal = logits[maxIdx]
                val tagName = idToTag.getOrElse(maxIdx) { "?" }
                Log.d("KIE_DEBUG", "  Token $i: logits=[$logitStr...], pred=$maxIdx($tagName), max=%.3f".format(maxVal))
            }
        }

        val tags = ArrayList<String>(T)
        for (i in 0 until T) {
            val logits = outLogits[0][i]
            val tagId = logits.indices.maxByOrNull { logits[it] } ?: 0
            tags.add(idToTag.getOrElse(tagId) { "O" })
        }
        return tags
    }


}