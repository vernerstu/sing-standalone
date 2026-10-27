package org.francasio.sing.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalStemAudioProcessor(
    context: Context,
    moduleApkPath: String,
    onEngineReady: () -> Unit,
    onEngineError: (String) -> Unit
) : BaseAudioProcessor() {
    private val singProcessor = SingAudioProcessor(
        context = context.applicationContext,
        moduleApkPath = moduleApkPath
    )
    private val adapter = ProcessorBufferAdapter()
    private var released = false
    private var passThroughOnly = false

    val isEngineReady: Boolean
        get() = singProcessor.isEngineReady

    var vocalLevel: Float
        get() = singProcessor.vocalLevel
        set(value) {
            singProcessor.vocalLevel = value.coerceIn(0f, 1f)
        }

    init {
        singProcessor.onEngineReadyListener = onEngineReady
        singProcessor.onEngineErrorListener = onEngineError
        singProcessor.isEnabled = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        passThroughOnly = inputAudioFormat.channelCount > 2
        adapter.inputAudioFormat = InputAudioFormat(
            encoding = inputAudioFormat.encoding,
            channelCount = inputAudioFormat.channelCount,
            sampleRate = inputAudioFormat.sampleRate
        )
        if (passThroughOnly) return inputAudioFormat

        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (passThroughOnly) {
            replaceOutputBuffer(inputBuffer.remaining())
                .put(inputBuffer)
                .flip()
            return
        }

        adapter.outputBuffer = null
        singProcessor.processQueueInput(adapter, inputBuffer)
        val processed = adapter.outputBuffer ?: return
        if (processed.hasRemaining()) {
            replaceOutputBuffer(processed.remaining())
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(processed)
                .flip()
        }
    }

    override fun getOutput(): ByteBuffer {
        val queuedOutput = super.getOutput()
        if (queuedOutput.hasRemaining() || passThroughOnly) return queuedOutput

        val drained = singProcessor.drainPendingOutput(adapter) ?: return queuedOutput
        replaceOutputBuffer(drained.remaining())
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(drained)
            .flip()
        return super.getOutput()
    }

    override fun isEnded(): Boolean {
        return super.isEnded() && singProcessor.isEnded()
    }

    override fun onQueueEndOfStream() {
        singProcessor.onEndOfStream()
    }

    override fun onFlush() {
        singProcessor.reset()
    }

    override fun onReset() {
        close()
    }

    fun close() {
        if (released) return
        released = true
        singProcessor.isEnabled = false
        singProcessor.release()
    }

    private data class InputAudioFormat(
        @JvmField val encoding: Int,
        @JvmField val channelCount: Int,
        @JvmField val sampleRate: Int
    )

    private class ProcessorBufferAdapter {
        @JvmField
        var inputAudioFormat: InputAudioFormat? = null

        @JvmField
        var level: Float = 0f

        @JvmField
        var mode: Int = 0

        var outputBuffer: ByteBuffer? = null
            private set

        fun replaceOutputBuffer(size: Int): ByteBuffer {
            return ByteBuffer.allocateDirect(size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .also { outputBuffer = it }
        }
    }
}
