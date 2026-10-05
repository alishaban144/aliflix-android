package com.aliflix.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream

/** Container text packets after Media3 parsing, with the extractor's media PTS.
 * Only a complete, unseeked container pass is a full subtitle reference. HLS
 * subtitle renditions are not fetched or enabled just to obtain a reference.
 */
internal class EmbeddedSyncReference {
    data class Reference(val cues: List<SubtitleCue>, val complete: Boolean)
    private val tracks = mutableMapOf<Int, MutableList<SubtitleCue>>()
    private var complete = false
    private var valid = true
    private var epoch = 0L
    @Synchronized fun reset() { tracks.clear(); complete = false; valid = true; epoch++ }
    @Synchronized fun token() = epoch
    @Synchronized fun invalidate(token: Long) { if (token == epoch) { complete = false; valid = false } }
    @Synchronized fun finish(token: Long) { if (token == epoch && valid) complete = true }
    @Synchronized fun add(token: Long, track: Int, cues: List<SubtitleCue>) {
        if (token != epoch || !valid) return
        val list = tracks.getOrPut(track) { mutableListOf() }
        if (list.size + cues.size > 20000) { valid = false; return }
        list.addAll(cues.filter { it.startSeconds >= 0 && it.endSeconds > it.startSeconds && it.endSeconds <= 21600 && it.text.isNotBlank() })
    }
    @Synchronized fun preferred(): Reference? {
        if (!valid || !complete) return null
        return observed()
    }
    @Synchronized fun observed(): Reference? {
        if (!valid) return null
        val longest = tracks.values.filter { it.size >= 4 }.maxByOrNull { list -> list.maxOf { it.endSeconds } } ?: return null
        return Reference(longest.distinct().sortedBy { it.startSeconds }, complete)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ReferenceExtractorsFactory(private val reference: EmbeddedSyncReference) : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(factory); return this
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        delegate.setTextTrackTranscodingEnabled(enabled); return this
    }
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
    override fun createExtractors(uri: Uri, headers: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, headers))
    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map<Extractor, Extractor> { extractor ->
        // A separately loaded SRT/VTT is the target, never an embedded reference.
        if (extractor !is androidx.media3.extractor.mkv.MatroskaExtractor &&
            extractor !is androidx.media3.extractor.mp4.Mp4Extractor &&
            extractor !is androidx.media3.extractor.mp4.FragmentedMp4Extractor) return@map extractor
        object : Extractor by extractor {
            private val token = reference.token()
            override fun init(output: ExtractorOutput) {
                extractor.init(object : ForwardingExtractorOutput(output) {
                    override fun track(id: Int, type: Int): TrackOutput {
                        val original = super.track(id, type)
                        return if (type == C.TRACK_TYPE_TEXT) ReferenceTrackOutput(original, reference, token, id) else original
                    }
                })
            }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                val result = extractor.read(input, seekPosition)
                if (result == Extractor.RESULT_END_OF_INPUT) reference.finish(token)
                return result
            }
            override fun seek(position: Long, timeUs: Long) {
                if (position != 0L || timeUs != 0L) reference.invalidate(token)
                extractor.seek(position, timeUs)
            }
        }
    }.toTypedArray()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ReferenceTrackOutput(
    delegate: TrackOutput, private val reference: EmbeddedSyncReference,
    private val token: Long, private val track: Int,
) : ForwardingTrackOutput(delegate) {
    private var accepted = false
    private val data = ByteArrayOutputStream()
    private fun invalidate() { accepted = false; data.reset(); reference.invalidate(token) }
    override fun format(format: Format) {
        accepted = format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES &&
            !format.id.orEmpty().contains("aliflix-external") && format.cryptoType == C.CRYPTO_TYPE_NONE
        super.format(format)
    }
    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (!accepted) return
        if (data.size() + length > 1024 * 1024) { invalidate(); return }
        data.write(bytes, offset, length)
    }
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        super.sampleData(DataReader { buffer, offset, count ->
            input.read(buffer, offset, count).also { n -> if (n > 0 && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) append(buffer, offset, n) }
        }, length, allowEndOfInput, sampleDataPart)
    override fun sampleData(input: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) append(input.data, input.position, length)
        super.sampleData(input, length, sampleDataPart)
    }
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (accepted) runCatching {
            val bytes = data.toByteArray()
            val start = bytes.size - offset - size
            if (start >= 0 && timeUs != C.TIME_UNSET && cryptoData == null && offset >= 0) {
                val decoded = CueDecoder().decode(timeUs, bytes, start, size)
                if (decoded.durationUs != C.TIME_UNSET && decoded.startTimeUs != C.TIME_UNSET) reference.add(token, track,
                    decoded.cues.mapNotNull { cue -> cue.text?.toString()?.let {
                        SubtitleCue(decoded.startTimeUs / 1e6, decoded.endTimeUs / 1e6, it)
                    } })
                else invalidate()
            } else invalidate()
            data.reset()
            if (offset in 1..bytes.size) data.write(bytes, bytes.size - offset, offset)
        }.onFailure { invalidate() }
        super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
