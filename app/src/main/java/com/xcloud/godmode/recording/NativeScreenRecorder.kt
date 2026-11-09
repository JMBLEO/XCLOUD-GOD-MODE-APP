package com.xcloud.godmode.recording

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * NativeScreenRecorder: Records native game stream video and audio directly into an MP4 container
 * using MediaMuxer, and exports uncompressed PNG screenshots.
 */
class NativeScreenRecorder {
    companion object {
        private const val TAG = "NativeScreenRecorder"
    }

    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    var isRecording: Boolean = false
        private set

    fun startRecording(outputFile: File, videoFormat: MediaFormat, audioFormat: MediaFormat? = null): Boolean {
        if (isRecording) return false

        return try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = muxer.addTrack(videoFormat)
            if (audioFormat != null) {
                audioTrackIndex = muxer.addTrack(audioFormat)
            }
            muxer.start()
            mediaMuxer = muxer
            isMuxerStarted = true
            isRecording = true
            Log.i(TAG, "Recording started to ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaMuxer", e)
            stopRecording()
            false
        }
    }

    fun writeVideoSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!isRecording || !isMuxerStarted || videoTrackIndex < 0) return
        try {
            mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing video sample", e)
        }
    }

    fun writeAudioSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!isRecording || !isMuxerStarted || audioTrackIndex < 0) return
        try {
            mediaMuxer?.writeSampleData(audioTrackIndex, buffer, bufferInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio sample", e)
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaMuxer", e)
        }
        mediaMuxer = null
        isMuxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        Log.i(TAG, "Recording stopped.")
    }

    fun savePngScreenshot(bitmap: Bitmap, targetFile: File): Boolean {
        return try {
            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Saved PNG screenshot to ${targetFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save PNG screenshot", e)
            false
        }
    }
}
