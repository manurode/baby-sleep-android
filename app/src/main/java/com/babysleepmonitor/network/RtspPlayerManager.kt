package com.babysleepmonitor.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Manages ExoPlayer for RTSP video streaming.
 */
class RtspPlayerManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RtspPlayerManager"
    }
    
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentUri: String? = null
    
    /**
     * Listener for player state changes
     */
    interface PlayerStateListener {
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onError(error: String)
        fun onBuffering(isBuffering: Boolean)
    }
    
    private var stateListener: PlayerStateListener? = null
    
    fun setPlayerStateListener(listener: PlayerStateListener?) {
        stateListener = listener
    }
    
    /**
     * Initializes the player and attaches it to the PlayerView.
     * @param playerView The PlayerView to attach the player to
     */
    fun initialize(playerView: PlayerView) {
        this.playerView = playerView
        
        if (player == null) {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "Player isPlaying: $isPlaying")
                        stateListener?.onIsPlayingChanged(isPlaying)
                    }
                    
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateStr = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d(TAG, "Playback state: $stateStr")
                        stateListener?.onBuffering(playbackState == Player.STATE_BUFFERING)
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.message}")
                        stateListener?.onError(error.message ?: "Unknown playback error")
                    }
                })
            }
        }
        
        playerView.player = player
    }
    
    /**
     * Plays an RTSP stream.
     * @param rtspUri The RTSP URI to play
     * @param username Optional username for RTSP authentication
     * @param password Optional password for RTSP authentication
     */
    @OptIn(UnstableApi::class)
    fun play(rtspUri: String, username: String? = null, password: String? = null) {
        val player = this.player ?: run {
            Log.e(TAG, "Player not initialized. Call initialize() first.")
            return
        }
        
        Log.d(TAG, "Playing RTSP stream: $rtspUri")
        currentUri = rtspUri
        
        // Build URI with credentials if provided
        val uriWithAuth = if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            // Insert credentials into RTSP URL: rtsp://user:pass@host:port/path
            val uri = Uri.parse(rtspUri)
            Uri.Builder()
                .scheme(uri.scheme)
                .encodedAuthority("$username:$password@${uri.host}:${uri.port}")
                .encodedPath(uri.encodedPath)
                .encodedQuery(uri.encodedQuery)
                .build()
                .toString()
        } else {
            rtspUri
        }
        
        val mediaItem = MediaItem.fromUri(uriWithAuth)
        
        // Create RTSP media source
        val rtspMediaSource = RtspMediaSource.Factory()
            .createMediaSource(mediaItem)
        
        player.setMediaSource(rtspMediaSource)
        player.prepare()
        player.playWhenReady = true
    }
    
    /**
     * Stops playback.
     */
    fun stop() {
        Log.d(TAG, "Stopping playback")
        player?.stop()
        currentUri = null
    }
    
    /**
     * Pauses playback.
     */
    fun pause() {
        player?.pause()
    }
    
    /**
     * Resumes playback.
     */
    fun resume() {
        player?.play()
    }
    
    /**
     * Returns whether the player is currently playing.
     */
    fun isPlaying(): Boolean = player?.isPlaying == true
    
    /**
     * Returns the current stream URI.
     */
    fun getCurrentUri(): String? = currentUri
    
    /**
     * Releases the player resources.
     * Should be called when the player is no longer needed.
     */
    fun release() {
        Log.d(TAG, "Releasing player")
        playerView?.player = null
        player?.release()
        player = null
        playerView = null
        currentUri = null
        stateListener = null
    }
}
