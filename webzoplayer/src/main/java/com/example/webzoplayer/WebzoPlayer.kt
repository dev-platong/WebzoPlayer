package com.example.webzoplayer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class WebzoPlayer(surface: Surface) : HTMLVideoElement {

  private val playerThread = PlayerThread(surface)

  fun terminate() {
    playerThread.interrupt()
  }

  override var src: String = ""
    set(value) {
      playerThread.src = value
      field = value
    }

  override fun play() {
    playerThread.start()
  }

  private inner class PlayerThread(private val surface: Surface) : Thread() {
    private val TAG = "PlayerThread"
    private var audioTrack: AudioTrack? = null
    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private var extractorAudioIndex: Int? = null
    private var extractorVideoIndex: Int? = null

    var src: String = ""

    override fun run() {
      extractor = initializeDecoder()
      val videoDecoder = checkNotNull(videoDecoder)
      val audioDecoder = checkNotNull(audioDecoder)

      // Media codec state is Flushed.
      videoDecoder.start()
      audioDecoder.start()

      val videoInputBuffers = videoDecoder.inputBuffers
      val audioInputBuffers = audioDecoder.inputBuffers
      val audioOutputBuffers = audioDecoder.outputBuffers
      val mediaCodecBufferInfo = MediaCodec.BufferInfo()

      var isEOS = false
      val startTimeMs = SystemClock.elapsedRealtime()

      while (!interrupted()) {
        if (!isEOS) {
          // Media codec state is Running.
          val videoInputBufferIndex = videoDecoder.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT)
          val audioInputBufferIndex = audioDecoder.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT)
          val isInputBufferAvailable = videoInputBufferIndex >= 0 && audioInputBufferIndex >= 0
          if (isInputBufferAvailable) {
            isEOS = queueSampleFromExtractorToInputBuffer(
              videoInputBufferIndex,
              videoInputBuffers,
              audioInputBufferIndex,
              audioInputBuffers
            )
          }
        }
        val videoOutputBufferIndex =
          videoDecoder.dequeueOutputBuffer(mediaCodecBufferInfo, DEQUEUE_OUTPUT_TIMEOUT)
        val audioOutputBufferIndex =
          audioDecoder.dequeueOutputBuffer(mediaCodecBufferInfo, DEQUEUE_OUTPUT_TIMEOUT)

        when {
          audioOutputBufferIndex >= 0 -> {
            val audioOutputBuffer = audioOutputBuffers[audioOutputBufferIndex]
            val chunk = ByteArray(mediaCodecBufferInfo.size)
            audioOutputBuffer.get(chunk)
            audioOutputBuffer.clear()
            if (chunk.isNotEmpty()) {
              audioTrack!!.write(chunk, 0, chunk.size)
            }
            audioDecoder.releaseOutputBuffer(audioOutputBufferIndex, false)
          }
          audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val format = audioDecoder.outputFormat;
            audioTrack!!.playbackRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          }
        }

        when (videoOutputBufferIndex) {
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Output format changed.")
          MediaCodec.INFO_TRY_AGAIN_LATER -> Log.d(TAG, "dequeue output buffer timeout.")
          else -> {
            while (isPlaybackTooFast(mediaCodecBufferInfo.presentationTimeUs / 1000, startTimeMs)) {
              try {
                sleep(10)
              } catch (e: java.lang.Exception) {
                Log.v(TAG, "Too many sleep.")
                break
              }
            }
            videoDecoder.releaseOutputBuffer(videoOutputBufferIndex, true)
          }
        }
        if (mediaCodecBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
          Log.d(TAG_LOG_OMNISCIENCE, LABEL_LOG_DEQUEUE_EOS)
          break
        }
      }

      audioTrack?.stop()
      audioDecoder.stop()
      videoDecoder.stop()
      audioDecoder.release()
      videoDecoder.release()
      extractor?.release()
    }

    private fun isPlaybackTooFast(
      outputBufferPresentationTimeMs: Long,
      startTimeMs: Long
    ): Boolean {
      return outputBufferPresentationTimeMs > SystemClock.elapsedRealtime() - startTimeMs
    }

    // return stopped#configured state decoder and extractor
    private fun initializeDecoder(): MediaExtractor {
      val newExtractor = MediaExtractor()
      newExtractor.setDataSource(src)

      var newVideoDecoder: MediaCodec? = null
      var newAudioDecoder: MediaCodec? = null
      for (i in 0 until newExtractor.trackCount) {
        val format = newExtractor.getTrackFormat(i)
        val mime =
          format.getString(MediaFormat.KEY_MIME) ?: throw Exception("Panic: Mime is missing.")
        if (mime.startsWith("audio/")) {
          newAudioDecoder = MediaCodec.createDecoderByType(mime)
          // format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
          newAudioDecoder.configure(format, null, null, 0)

          val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          val channelConfig = AudioFormat.CHANNEL_OUT_MONO
          val audioFormat = AudioFormat.ENCODING_PCM_16BIT
          val audioBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
          audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            audioBufferSize,
            AudioTrack.MODE_STREAM
          )
          audioTrack!!.play()

          extractorAudioIndex = i
        } else if (mime.startsWith("video/")) {
          newExtractor.selectTrack(i)
          newVideoDecoder =
            MediaCodec.createDecoderByType(mime) // Codec state is Stopped#Uninitialized
          // Format is an input data format coz MediaCodec is decoder. SEE: https://developer.android.com/reference/android/media/MediaCodec#configure(android.media.MediaFormat,%20android.view.Surface,%20android.media.MediaCrypto,%20int)
          // Codec state is Stopped#Configured
          newVideoDecoder.configure(format, surface, null, 0)
          extractorVideoIndex = i
        }
      }
      if (newVideoDecoder == null || newAudioDecoder == null) {
        throw Exception("Panic: Can't find decoder on this device.")
      }
      audioDecoder = newAudioDecoder
      videoDecoder = newVideoDecoder
      return newExtractor
    }

    private fun queueSampleFromExtractorToInputBuffer(
      videoInputBufferIndex: Int,
      videoInputBuffers: Array<ByteBuffer>,
      audioInputBufferIndex: Int,
      audioInputBuffers: Array<ByteBuffer>,
    ): Boolean {
      val audioDecoder = checkNotNull(audioDecoder)
      val videoDecoder = checkNotNull(videoDecoder)
      val extractor = checkNotNull(extractor)
      val extractorAudioIndex = checkNotNull(extractorAudioIndex)
      val extractorVideoIndex = checkNotNull(extractorVideoIndex)

      extractor.selectTrack(extractorAudioIndex)
      Log.d("ABEMAA audioInputBufferIndex", audioInputBufferIndex.toString())
      val audioInputBuffer = audioInputBuffers[audioInputBufferIndex]
      val audioSampleSize = extractor.readSampleData(audioInputBuffer, 0)

      Log.d("ABEMAA audioSampleSize", audioSampleSize.toString())

      extractor.selectTrack(extractorVideoIndex)
      val videoInputBuffer = videoInputBuffers[videoInputBufferIndex]
      val videoSampleSize = extractor.readSampleData(videoInputBuffer, 0)

      Log.d("ABEMAA video", videoSampleSize.toString())

      // FIXME: videoとaudioのサンプルが同じ長さ前提になってる
      val videoIsEOS = videoSampleSize < 0
      if (videoIsEOS) {
        // Media codec transitions to the EOS sub-state.
        // The codec no longer accepts further input buffers but still generates output buffer
        // until reach EOS.
        // SEE: https://developer.android.com/reference/android/media/MediaCodec#:~:text=When%20you%20queue%20an%20input%20buffer%20with%20the%20end%2Dof%2Dstream%20marker%2C%20the%20codec%20transitions%20to%20the%20End%2Dof%2DStream%20sub%2Dstate
        audioDecoder.queueInputBuffer(
          audioInputBufferIndex,
          0,
          0,
          0,
          MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        videoDecoder.queueInputBuffer(
          videoInputBufferIndex,
          0,
          0,
          0,
          MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        Log.d(TAG_LOG_OMNISCIENCE, LABEL_LOG_QUEUE_EOS)
      } else {
        extractor.selectTrack(extractorAudioIndex)
        audioDecoder.queueInputBuffer(
          audioInputBufferIndex,
          0,
          audioSampleSize,
          extractor.sampleTime,
          0
        )
        extractor.advance()
        extractor.selectTrack(extractorVideoIndex)
        videoDecoder.queueInputBuffer(
          videoInputBufferIndex,
          0,
          videoSampleSize,
          extractor.sampleTime,
          0
        )
        extractor.advance()
      }
      return videoIsEOS
    }
  }

  companion object {
    val TAG_LOG_OMNISCIENCE = "LOG_OMNISCIENCE"
    val LABEL_LOG_QUEUE_EOS = "Queue EOS Flag to input buffer."
    val LABEL_LOG_DEQUEUE_EOS = "Dequeue EOS Flag from output buffer."
    val DEQUEUE_INPUT_TIMEOUT: Long = 10000
    val DEQUEUE_OUTPUT_TIMEOUT: Long = 10000
  }
}