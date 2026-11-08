package pear.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaNotificationManager(
    context: Context,
    private val onTogglePlay: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeekTo: (Long) -> Unit
) {
    companion object {
        const val CHANNEL_ID = "pear_remote_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PREVIOUS = "pear.remote.ACTION_PREVIOUS"
        const val ACTION_PLAY_PAUSE = "pear.remote.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "pear.remote.ACTION_NEXT"

        @Volatile
        var activeCallbackHandler: MediaNotificationManager? = null
    }

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageLoader = ImageLoader(appContext)

    private var cachedCoverUrl: String? = null
    private var cachedCoverBitmap: Bitmap? = null
    private var lastSubmittedState: PlayerUiState? = null

    val mediaSession: MediaSessionCompat = MediaSessionCompat(appContext, "PearRemoteSession").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                onTogglePlay()
            }

            override fun onPause() {
                onTogglePlay()
            }

            override fun onSkipToNext() {
                onNext()
            }

            override fun onSkipToPrevious() {
                onPrevious()
            }

            override fun onSeekTo(pos: Long) {
                onSeekTo(pos / 1000L)
            }
        })
        isActive = true
    }

    init {
        activeCallbackHandler = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pear Desktop Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением Pear Desktop"
                setShowBadge(false)
            }
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun updateNotification(state: PlayerUiState) {
        lastSubmittedState = state

        val url = state.coverUrl
        if (!url.isNullOrBlank() && url != cachedCoverUrl) {
            cachedCoverUrl = url
            scope.launch {
                try {
                    val request = ImageRequest.Builder(appContext)
                        .data(url)
                        .allowHardware(false)
                        .build()
                    val result = imageLoader.execute(request)
                    if (result is SuccessResult) {
                        cachedCoverBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        lastSubmittedState?.let { publishSessionAndNotification(it) }
                    }
                } catch (_: Exception) {
                }
            }
        } else if (url.isNullOrBlank()) {
            cachedCoverUrl = null
            cachedCoverBitmap = null
        }

        publishSessionAndNotification(state)
    }

    private fun publishSessionAndNotification(state: PlayerUiState) {
        try {
            val playbackStateCode = if (state.isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }

            val playbackState = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    playbackStateCode,
                    state.currentProgressSec * 1000L,
                    if (state.isPlaying) 1.0f else 0.0f
                )
                .build()
            mediaSession.setPlaybackState(playbackState)

            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.trackTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.trackArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.totalDurationSec * 1000L)

            cachedCoverBitmap?.let { bitmap ->
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            }
            mediaSession.setMetadata(metadataBuilder.build())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }

            val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                appContext,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val prevPendingIntent = buildActionPendingIntent(ACTION_PREVIOUS, 1)
            val playPausePendingIntent = buildActionPendingIntent(ACTION_PLAY_PAUSE, 2)
            val nextPendingIntent = buildActionPendingIntent(ACTION_NEXT, 3)

            val playPauseIcon = if (state.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            val playPauseLabel = if (state.isPlaying) "Пауза" else "Воспроизвести"

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(state.trackTitle)
                .setContentText(state.trackArtist)
                .setSubText("Pear Desktop Remote")
                .setLargeIcon(cachedCoverBitmap)
                .setContentIntent(contentPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(state.isPlaying)
                .addAction(android.R.drawable.ic_media_previous, "Назад", prevPendingIntent)
                .addAction(playPauseIcon, playPauseLabel, playPausePendingIntent)
                .addAction(android.R.drawable.ic_media_next, "Вперёд", nextPendingIntent)
                .setStyle(
                    MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .build()

            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun buildActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MediaActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun release() {
        try {
            mediaSession.isActive = false
            mediaSession.release()
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    class MediaActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val handler = activeCallbackHandler
            if (handler != null) {
                when (action) {
                    ACTION_PREVIOUS -> handler.onPrevious()
                    ACTION_PLAY_PAUSE -> handler.onTogglePlay()
                    ACTION_NEXT -> handler.onNext()
                }
            } else {
                val manager = PearRemoteManager.activeInstance ?: return
                when (action) {
                    ACTION_PREVIOUS -> manager.previous()
                    ACTION_PLAY_PAUSE -> manager.togglePlay()
                    ACTION_NEXT -> manager.next()
                }
            }
        }
    }
}
