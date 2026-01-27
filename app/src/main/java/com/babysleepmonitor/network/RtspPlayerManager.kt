package com.babysleepmonitor.network

import android.content.Context
import android.net.Uri
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import android.graphics.Bitmap
import android.view.TextureView
import android.view.ViewGroup
import java.util.ArrayList

/**
 * Manages LibVLC for robust RTSP video streaming.
 * Replaces ExoPlayer implementation to handle complex auth/codecs.
 */
class RtspPlayerManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RtspPlayerManager"
    }
    
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var stateListener: PlayerStateListener? = null
    
    /**
     * Listener for player state changes
     */
    interface PlayerStateListener {
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onError(error: String)
        fun onBuffering(isBuffering: Boolean)
    }
    
    fun setPlayerStateListener(listener: PlayerStateListener?) {
        stateListener = listener
    }
    
    /**
     * Initializes the player and attaches it to the VLCVideoLayout.
     * @param layout The VLCVideoLayout to attach the player to
     */
    fun initialize(layout: VLCVideoLayout) {
        this.videoLayout = layout
    }

    /**
     * Plays an RTSP stream.
     */
    fun play(rtspUri: String, username: String? = null, password: String? = null) {
        try {
            if (videoLayout == null) {
                Log.e(TAG, "VideoLayout not initialized!")
                stateListener?.onError("Video layout not initialized")
                return
            }
            
            // Release previous instance
            releasePlayer()

            val options = ArrayList<String>()
            // Force TCP for better reliability over WiFi
            options.add("--rtsp-tcp")
            // Network caching (latency vs stability). 600ms is a good balance.
            options.add("--network-caching=600")
            // Drop late frames
            options.add("--drop-late-frames")
            options.add("--skip-frames")
            // Verbose logs for debugging
            // options.add("-vvv") 

            libVlc = LibVLC(context, options)
            mediaPlayer = MediaPlayer(libVlc)

            // Enable TextureView (true as last arg) to allow frame capture via getBitmap
            mediaPlayer?.attachViews(videoLayout!!, null, false, true)

            mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        Log.d(TAG, "LibVLC: Playing")
                        stateListener?.onIsPlayingChanged(true)
                        stateListener?.onBuffering(false)
                    }
                    MediaPlayer.Event.Buffering -> {
                         // Only map buffering start (0) or significant changes?
                         // VLC events send buffering percentage.
                         if (event.buffering == 100f) {
                             stateListener?.onBuffering(false)
                         } else if (event.buffering < 100f) {
                             // Optional: notify buffering
                         }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e(TAG, "LibVLC: Encountered Error")
                        stateListener?.onError("Playback Error")
                        stateListener?.onIsPlayingChanged(false)
                    }
                    MediaPlayer.Event.Stopped -> {
                         Log.d(TAG, "LibVLC: Stopped")
                         stateListener?.onIsPlayingChanged(false)
                    }
                }
            }

            // Manually inject request credentials into URI if provided and not already in URI
            // LibVLC handles rtsp://user:pass@host very well.
            // Check if we have EITHER username OR password (some cameras might use blank user or blank pass)
            val finalUri = if (!username.isNullOrBlank() || !password.isNullOrBlank()) {
                 val user = username ?: ""
                 val pass = password ?: ""
                 
                 if (rtspUri.contains(user) && rtspUri.contains(pass)) {
                     rtspUri
                 } else {
                     try {
                         // Parse URI to inject credentials safely
                         val uriObj = Uri.parse(rtspUri)
                         val scheme = uriObj.scheme ?: "rtsp"
                         val host = uriObj.host ?: ""
                         val port = if (uriObj.port != -1) ":${uriObj.port}" else ""
                         val path = uriObj.path ?: ""
                         val query = if (uriObj.query != null) "?${uriObj.query}" else ""
                         
                         val encUser = Uri.encode(user)
                         val encPass = Uri.encode(pass)
                         
                         "$scheme://$encUser:$encPass@$host$port$path$query"
                     } catch (e: Exception) {
                         // Fallback to simple string replacement if parsing fails
                         Log.w(TAG, "URI parse failed, using simple injection")
                         val schemeEnd = rtspUri.indexOf("://") + 3
                         val scheme = rtspUri.substring(0, schemeEnd)
                         val rest = rtspUri.substring(schemeEnd)
                         "$scheme${Uri.encode(user)}:${Uri.encode(pass)}@$rest"
                     }
                 }
            } else {
                rtspUri
            }
            
            Log.d(TAG, "Playing RTSP: $finalUri")
            
            val media = Media(libVlc, Uri.parse(finalUri))
            // Enable Hardware Decoding
            media.setHWDecoderEnabled(true, false)
            
            // Add options specific to this media if needed
            // media.addOption(":rtsp-tcp")
            
            mediaPlayer?.media = media
            media.release() // Media is now owned by player

            mediaPlayer?.play()

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LibVLC", e)
            stateListener?.onError("Init Error: ${e.message}")
        }
    }
    
    /**
     * Stops playback.
     */
    fun stop() {
        Log.d(TAG, "Stopping playback")
        mediaPlayer?.stop()
        stateListener?.onIsPlayingChanged(false)
    }
    
    /**
     * Pauses playback.
     */
    fun pause() {
        mediaPlayer?.pause()
    }
    
    /**
     * Resumes playback.
     */
    fun resume() {
        mediaPlayer?.play()
    }
    
    /**
     * Returns whether the player is currently playing.
     */
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    
    /**
     * Returns the current stream URI (not tracked currently).
     */
    fun getCurrentUri(): String? = null
    
    /**
     * Releases the player resources.
     * Should be called when the player is no longer needed.
     */
    fun release() {
        Log.d(TAG, "Releasing player")
        releasePlayer()
        videoLayout = null
        stateListener = null
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.detachViews()
            mediaPlayer?.release()
            mediaPlayer = null
            libVlc?.release()
            libVlc = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player", e)
        }
    }

    /**
     * Captures the current video frame as a Bitmap.
     * Returns null if player is not ready or texture view is unavailable.
     */
    fun getCurrentFrame(): Bitmap? {
        val view = videoLayout?.let { findTextureView(it) } ?: return null
        // Create a software-backed bitmap to avoid HWUI "Unable to draw content from GPU" errors
        // and ensures compatibility with OpenCV and ML Kit.
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return view.getBitmap(bitmap)
    }

    private fun findTextureView(group: ViewGroup): TextureView? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextureView) {
                return child
            }
            if (child is ViewGroup) {
                val found = findTextureView(child)
                if (found != null) return found
            }
        }
        return null
    }
}
