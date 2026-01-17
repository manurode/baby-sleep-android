package com.babysleepmonitor.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Parses MJPEG streams from the Flask server's /video_feed endpoint.
 * 
 * The server sends data in this format:
 * --frame\r\n
 * Content-Type: image/jpeg\r\n
 * \r\n
 * <JPEG bytes>
 * \r\n
 * --frame\r\n
 * ...
 */
class MjpegInputStream(inputStream: InputStream) {
    
    companion object {
        private const val TAG = "MjpegInputStream"
        
        // MJPEG boundary marker
        private val BOUNDARY = "--frame".toByteArray()
        private val CONTENT_TYPE_HEADER = "Content-Type:".toByteArray()
        private val HEADER_END = "\r\n\r\n".toByteArray()
        
        // JPEG markers
        private const val JPEG_START_MARKER = 0xFF
        private const val JPEG_START_MARKER_2 = 0xD8
        private const val JPEG_END_MARKER = 0xFF
        private const val JPEG_END_MARKER_2 = 0xD9
        
        // Buffer sizes
        private const val BUFFER_SIZE = 32 * 1024  // 32KB read buffer
        private const val INITIAL_FRAME_SIZE = 64 * 1024  // 64KB initial frame buffer
    }
    
    private val stream = BufferedInputStream(inputStream, BUFFER_SIZE)
    private val frameBuffer = ByteArrayOutputStream(INITIAL_FRAME_SIZE)
    
    /**
     * Read the next JPEG frame from the MJPEG stream.
     * 
     * @return Bitmap of the frame, or null if end of stream or error
     */
    @Throws(IOException::class)
    fun readFrame(): Bitmap? {
        frameBuffer.reset()
        
        // Skip to the next boundary
        if (!skipToBoundary()) {
            Log.d(TAG, "Could not find boundary marker")
            return null
        }
        
        // Skip headers until we find the empty line
        if (!skipHeaders()) {
            Log.d(TAG, "Could not skip headers")
            return null
        }
        
        // Read JPEG data until next boundary
        val jpegBytes = readJpegData() ?: return null
        
        // Decode JPEG to Bitmap
        return try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JPEG frame: ${e.message}")
            null
        }
    }
    
    /**
     * Skip bytes until we find the boundary marker "--frame"
     */
    private fun skipToBoundary(): Boolean {
        val buffer = ByteArray(BOUNDARY.size)
        var matchIndex = 0
        
        while (true) {
            val byte = stream.read()
            if (byte == -1) return false
            
            if (byte.toByte() == BOUNDARY[matchIndex]) {
                buffer[matchIndex] = byte.toByte()
                matchIndex++
                if (matchIndex == BOUNDARY.size) {
                    // Found boundary, now skip the \r\n after it
                    skipLine()
                    return true
                }
            } else {
                matchIndex = 0
                // Check if current byte might be start of boundary
                if (byte.toByte() == BOUNDARY[0]) {
                    buffer[0] = byte.toByte()
                    matchIndex = 1
                }
            }
        }
    }
    
    /**
     * Skip header lines until we find an empty line (end of headers)
     */
    private fun skipHeaders(): Boolean {
        var consecutiveCrlf = 0
        var lastWasCr = false
        
        while (true) {
            val byte = stream.read()
            if (byte == -1) return false
            
            when (byte.toChar()) {
                '\r' -> lastWasCr = true
                '\n' -> {
                    if (lastWasCr) {
                        consecutiveCrlf++
                        if (consecutiveCrlf >= 2) {
                            // Found \r\n\r\n - end of headers
                            return true
                        }
                    }
                    lastWasCr = false
                }
                else -> {
                    consecutiveCrlf = 0
                    lastWasCr = false
                }
            }
        }
    }
    
    /**
     * Read JPEG data until we hit the next boundary marker.
     * We look for the JPEG end marker (0xFFD9) and then verify with boundary.
     */
    private fun readJpegData(): ByteArray? {
        frameBuffer.reset()
        
        var prevByte = -1
        var foundJpegEnd = false
        
        while (true) {
            val byte = stream.read()
            if (byte == -1) return null
            
            frameBuffer.write(byte)
            
            // Check for JPEG end marker (0xFFD9)
            if (prevByte == JPEG_END_MARKER && byte == JPEG_END_MARKER_2.toInt()) {
                foundJpegEnd = true
            }
            
            // After finding JPEG end, look for boundary to confirm end of frame
            if (foundJpegEnd) {
                val data = frameBuffer.toByteArray()
                
                // Check if we're seeing the start of a new boundary
                // The format after JPEG is: \r\n--frame
                if (byte == '-'.code) {
                    // Might be start of --frame, let's check by marking and reading ahead
                    stream.mark(10)
                    val nextByte = stream.read()
                    
                    if (nextByte == '-'.code) {
                        // Very likely boundary. Trim trailing bytes from frame data
                        // Remove the trailing \r\n- that we already read into the buffer
                        val trimmedData = trimTrailingBoundaryStart(data)
                        
                        // Reset stream to re-read the boundary for next frame
                        stream.reset()
                        stream.read() // Skip the '-' we just read
                        
                        return trimmedData
                    } else {
                        stream.reset()
                    }
                }
            }
            
            prevByte = byte
            
            // Safety limit: if frame is too large, something is wrong
            if (frameBuffer.size() > 5 * 1024 * 1024) { // 5MB limit
                Log.e(TAG, "Frame too large, aborting")
                return null
            }
        }
    }
    
    /**
     * Trim trailing bytes that are part of the boundary marker.
     * The JPEG data ends with 0xFFD9, followed by \r\n, then --frame
     * We may have captured some of \r\n-- before detecting the boundary
     */
    private fun trimTrailingBoundaryStart(data: ByteArray): ByteArray {
        var endIndex = data.size
        
        // Look for 0xFFD9 (JPEG end marker) and trim everything after
        for (i in data.size - 1 downTo 1) {
            if (data[i].toInt() and 0xFF == JPEG_END_MARKER_2.toInt() && 
                data[i - 1].toInt() and 0xFF == JPEG_END_MARKER) {
                endIndex = i + 1
                break
            }
        }
        
        return if (endIndex < data.size) {
            data.copyOf(endIndex)
        } else {
            data
        }
    }
    
    /**
     * Skip current line (until \n)
     */
    private fun skipLine() {
        while (true) {
            val byte = stream.read()
            if (byte == -1 || byte == '\n'.code) break
        }
    }
    
    /**
     * Close the underlying stream
     */
    fun close() {
        try {
            stream.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing stream: ${e.message}")
        }
    }
}
