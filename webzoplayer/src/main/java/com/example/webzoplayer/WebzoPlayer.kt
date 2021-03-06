package com.example.webzoplayer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class WebzoPlayer(private val surface: Surface) : HTMLVideoElement {

    private val TAG = this.javaClass.canonicalName

    @Volatile
    private var playerThread: PlayerThread? = null

    // XXX: スレッドが複数ある可能性今はないし複数は想定してない
    private var decoder: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var lastPausedPresentationTimeMs = 0L

    fun terminate() {
        checkNotNull(playerThread).interrupt()
    }

    override var src: String = ""

    override fun pause() {
        playerThread = null
    }

    override fun play() {
        if (playerThread != null) return
        if (decoder == null && extractor == null) {
            val (newDecoder, newExtractor) = initializeDecoder()
            decoder = newDecoder
            extractor = newExtractor
        }
        playerThread = PlayerThread(surface)
        playerThread!!.src = src
        playerThread!!.start()
    }

    // return stopped#configured state decoder and extractor
    private fun initializeDecoder(): Pair<MediaCodec, MediaExtractor> {
        val newExtractor = MediaExtractor()
        newExtractor.setDataSource(src)

        Log.d(TAG, "Demultiplexing media: $src")
        var newDecoder: MediaCodec? = null
        for (i in 0..newExtractor.trackCount) {
            val format = newExtractor.getTrackFormat(i)
            val mime =
                format.getString(MediaFormat.KEY_MIME) ?: throw Exception("Panic: Mime is missing.")
            Log.d(TAG, "Track $i: MimeType: $mime")
            if (!mime.startsWith("video/")) {
                // Audio tracks, caption, etc..
                continue
            }
            newExtractor.selectTrack(i)
            newDecoder =
                MediaCodec.createDecoderByType(mime) // Codec state is Stopped#Uninitialized
            // Format is an input data format coz MediaCodec is decoder. SEE: https://developer.android.com/reference/android/media/MediaCodec#configure(android.media.MediaFormat,%20android.view.Surface,%20android.media.MediaCrypto,%20int)
            // Codec state is Stopped#Configured
            newDecoder.configure(format, surface, null, 0)
            break
        }
        if (newDecoder == null) {
            throw Exception("Panic: Can't find decoder on this device.")
        }
        newDecoder.start()
        return newDecoder to newExtractor
    }

    private inner class PlayerThread(private val surface: Surface) : Thread() {
        private val TAG = "PlayerThread"

        var src: String = ""

        override fun run() {
            checkNotNull(decoder)
            checkNotNull(extractor)
            Log.d(TAG, "playback thread is started.")

            val inputBuffers = decoder!!.inputBuffers

            var isEOS = false
            val startTimeMs = SystemClock.elapsedRealtime()

            val currentThread = Thread.currentThread()

            while (!interrupted()) {
                if (currentThread != playerThread) {
                    Log.d(TAG, "playback thread is ended.")
                    lastPausedPresentationTimeMs = bufferInfo.presentationTimeUs / 1000
                    return
                }
                if (!isEOS) {
                    // Media codec state is Running.
                    val inputBufferIndex = decoder!!.dequeueInputBuffer(10000)
                    val isInputBufferAvailable = inputBufferIndex >= 0
                    if (isInputBufferAvailable) {
                        isEOS =
                            queueSampleFromExtractorToInputBuffer(inputBufferIndex, inputBuffers)
                    }
                }
                val outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 10000)
                when (outputBufferIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Output format changed.")
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Log.d(TAG, "dequeue output buffer timeout.")
                    else -> {
                        while (isPlaybackTooFast(
                                bufferInfo.presentationTimeUs / 1000,
                                startTimeMs
                            )
                        ) {
                            try {
                                Log.d(TAG, "Sleep is exec")
                                sleep(10)
                            } catch (e: java.lang.Exception) {
                                Log.v(TAG, "Too many sleep.")
                                break
                            }
                        }
                        decoder!!.releaseOutputBuffer(outputBufferIndex, true)
                    }
                }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG_LOG_OMNISCIENCE, LABEL_LOG_DEQUEUE_EOS)
                    break
                }
            }

            decoder!!.stop()
            decoder!!.release()
            extractor!!.release()
        }

        private fun isPlaybackTooFast(
            outputBufferPresentationTimeMs: Long,
            startTimeMs: Long
        ): Boolean {
            return outputBufferPresentationTimeMs > (SystemClock.elapsedRealtime() - startTimeMs + lastPausedPresentationTimeMs)
        }


        private fun queueSampleFromExtractorToInputBuffer(
            inputBufferIndex: Int,
            inputBuffers: Array<ByteBuffer>
        ): Boolean {
            assert(decoder != null)
            assert(extractor != null)
            val inputBuffer = inputBuffers[inputBufferIndex]
            val sampleSize = extractor!!.readSampleData(inputBuffer, 0)

            val isEOS = sampleSize < 0
            if (isEOS) {
                // Media codec transitions to the EOS sub-state.
                // The codec no longer accepts further input buffers but still generates output buffer
                // until reach EOS.
                // SEE: https://developer.android.com/reference/android/media/MediaCodec#:~:text=When%20you%20queue%20an%20input%20buffer%20with%20the%20end%2Dof%2Dstream%20marker%2C%20the%20codec%20transitions%20to%20the%20End%2Dof%2DStream%20sub%2Dstate
                decoder!!.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                Log.d(TAG_LOG_OMNISCIENCE, LABEL_LOG_QUEUE_EOS)
            } else {
                decoder!!.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    sampleSize,
                    extractor!!.sampleTime,
                    0
                )
                extractor!!.advance()
            }
            return isEOS
        }
    }

    companion object {
        val TAG_LOG_OMNISCIENCE = "LOG_OMNISCIENCE"
        val LABEL_LOG_QUEUE_EOS = "Queue EOS Flag to input buffer."
        val LABEL_LOG_DEQUEUE_EOS = "Dequeue EOS Flag from output buffer."
    }
}