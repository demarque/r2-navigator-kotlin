/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.media

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.*
import org.readium.r2.navigator.R
import org.readium.r2.navigator.audio.PublicationDataSource
import org.readium.r2.navigator.extensions.timeWithDuration
import org.readium.r2.shared.AudioSupport
import org.readium.r2.shared.extensions.tryOrNull
import org.readium.r2.shared.publication.*
import java.net.URL
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@AudioSupport
@OptIn(ExperimentalTime::class)
internal class ExoMediaPlayer(
    context: Context,
    mediaSession: MediaSessionCompat,
    media: PendingMedia
) : MediaPlayer, CoroutineScope by MainScope() {

    override var listener: MediaPlayer.Listener? = null

    private val publication: Publication = media.publication
    private val publicationId: PublicationId = media.publicationId

    private val player: ExoPlayer = SimpleExoPlayer.Builder(context).build().apply {
        audioAttributes = AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        setHandleAudioBecomingNoisy(true)
//        addAnalyticsListener(EventLogger(null))
    }

    private val notificationManager =
        PlayerNotificationManager.createWithNotificationChannel(
            context,
            MEDIA_CHANNEL_ID,
            R.string.r2_media_notification_channel_name,
            R.string.r2_media_notification_channel_description,
            MEDIA_NOTIFICATION_ID,
            DescriptionAdapter(mediaSession.controller),
            NotificationListener()
        ).apply {
            setMediaSessionToken(mediaSession.sessionToken)
            setPlayer(player)
            setSmallIcon(R.drawable.exo_notification_small_icon)
            setUsePlayPauseActions(true)
            setUseStopAction(false)
            setUseNavigationActions(false)
            setUseNavigationActionsInCompactView(false)
            setUseChronometer(false)
            setRewindIncrementMs(30.seconds.toLongMilliseconds())
            setFastForwardIncrementMs(30.seconds.toLongMilliseconds())
        }

    private val mediaSessionConnector = MediaSessionConnector(mediaSession)

    init {
        mediaSessionConnector.apply {
            setPlaybackPreparer(PlaybackPreparer())
            setQueueNavigator(QueueNavigator(mediaSession))
            setPlayer(player)
        }

        prepareTracklist()
        seekTo(media.locator)
    }

    override fun onDestroy() {
        cancel()
        notificationManager.setPlayer(null)
        player.stop(true)
        player.release()
    }

    private fun prepareTracklist() {
        val dataSourceFactory = PublicationDataSource.Factory(publication)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

        val trackSources = publication.readingOrder.map { link ->
            val uri = Uri.parse(link.href)
            mediaSourceFactory.createMediaSource(uri)
        }
        val tracklistSource = ConcatenatingMediaSource(*trackSources.toTypedArray())

        player.prepare(tracklistSource)
    }

    private fun seekTo(locator: Locator) {
        val readingOrder = publication.readingOrder
        val index = readingOrder.indexOfFirstWithHref(locator.href) ?: 0

        val duration = readingOrder[index].duration?.seconds
        val time = locator.locations.timeWithDuration(duration)
        player.seekTo(index, time?.toLongMilliseconds() ?: 0)
    }

    private inner class PlaybackPreparer : MediaSessionConnector.PlaybackPreparer {

        // We don't support any custom commands for now.
        override fun onCommand(player: Player, controlDispatcher: ControlDispatcher, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean = false

        override fun getSupportedPrepareActions(): Long =
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID

        override fun onPrepare(playWhenReady: Boolean) {}

        override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
            val locator = listener?.locatorFromMediaId(mediaId, extras) ?: return
            player.playWhenReady = playWhenReady
            player.stop()
            seekTo(locator)
        }

        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {}

        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {}

    }

    private inner class QueueNavigator(mediaSession: MediaSessionCompat) : TimelineQueueNavigator(mediaSession) {

        override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat =
            createMediaMetadata(publication.readingOrder[windowIndex]).description

    }

    private inner class NotificationListener : PlayerNotificationManager.NotificationListener {

        override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
            if (ongoing) {
                listener?.onNotificationPosted(notificationId, notification)
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            listener?.onNotificationCancelled(notificationId)
        }

    }

    private inner class DescriptionAdapter(private val controller: MediaControllerCompat) : PlayerNotificationManager.MediaDescriptionAdapter {

        var currentIconUri: Uri? = null
        var currentBitmap: Bitmap? = null

        override fun createCurrentContentIntent(player: Player): PendingIntent? =
            controller.sessionActivity

        override fun getCurrentContentText(player: Player): CharSequence? =
            controller.metadata.description.subtitle

        override fun getCurrentContentTitle(player: Player): CharSequence =
            controller.metadata.description.title ?: publication.metadata.title

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ): Bitmap? {
            val iconUri = controller.metadata.description.iconUri
            return if (currentIconUri != iconUri || currentBitmap == null) {
                currentIconUri = iconUri
                launch {
                    currentBitmap = iconUri?.let { resolveUriAsBitmap(it) }
                    currentBitmap?.let { callback.onBitmap(it) }
                }
                null
            } else {
                currentBitmap
            }
        }

        private suspend fun resolveUriAsBitmap(uri: Uri): Bitmap? {
            val link = publication.linkWithHref(uri.toString())
            return when {
                link != null -> {
                    publication.get(link).read()
                        .map { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        .getOrNull()
                }
                uri.scheme != null -> {
                    withContext(Dispatchers.IO) {
                        tryOrNull {
                            URL(uri.toString()).readBytes()
                                .let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }
                }
                else -> null
            }
        }

    }

    private fun createMediaMetadata(link: Link) = MediaMetadataCompat.Builder().apply {
        putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "${publicationId}#${link.href}")
        putString(MediaMetadataCompat.METADATA_KEY_TITLE, link.title)
        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, publication.linkWithRel("cover")?.href)
    }.build()

}

private const val MEDIA_CHANNEL_ID = "org.readium.r2.navigator.media"
private const val MEDIA_NOTIFICATION_ID = 0xb339 // Arbitrary number used to identify our notification