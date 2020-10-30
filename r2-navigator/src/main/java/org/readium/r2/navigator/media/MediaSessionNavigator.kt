/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.media

import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaControllerCompat.TransportControls
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.readium.r2.navigator.MediaNavigator
import org.readium.r2.navigator.extensions.sum
import org.readium.r2.navigator.media.extensions.*
import org.readium.r2.shared.AudioSupport
import org.readium.r2.shared.publication.*
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

/**
 * Rate at which the current locator is broadcasted during playback.
 */
private const val playbackPositionRefreshRate: Double = 2.0  // Hz

@OptIn(ExperimentalTime::class)
private val skipForwardInterval: Duration = 30.seconds
@OptIn(ExperimentalTime::class)
private val skipBackwardInterval: Duration = 30.seconds

@AudioSupport
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MediaSessionNavigator(
    override val publication: Publication,
    val publicationId: PublicationId,
    val controller: MediaControllerCompat
) : MediaNavigator, CoroutineScope by MainScope() {

    /**
     * Indicates whether the media session is loaded with a resource from this [publication]. This
     * is necessary because a single media session could be used to play multiple publications.
     */
    private val isActive: Boolean get() =
        controller.publicationId == publicationId

    // FIXME: ExoPlayer's media session connector doesn't handle the playback speed yet, so we need the player instance for now
    internal var player: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    private var playWhenReady: Boolean = false

    private val needsPlaying: Boolean get() =
        playWhenReady && !controller.playbackState.isPlaying

    /**
     * Duration of each reading order resource.
     */
    private val durations: List<Duration?> =
        publication.readingOrder.map { link -> link.duration?.takeIf { it > 0 }?.seconds }

    /**
     * Total duration of the publication.
     */
    private val totalDuration: Duration? =
        durations.sum().takeIf { it > 0.seconds }


    private val mediaMetadata = MutableStateFlow<MediaMetadataCompat?>(null)
    private val playbackState = MutableStateFlow<PlaybackStateCompat?>(null)
    private val playbackPosition = MutableStateFlow(0.seconds)

    init {
        controller.registerCallback(MediaControllerCallback())

        launch {
            combine(playbackPosition, mediaMetadata, ::createLocator)
                .filterNotNull()
                .collect { _currentLocator.value = it }
        }
    }

    private val transportControls: TransportControls get() = controller.transportControls

    /**
     * Observes recursively the playback position, as long as it is playing.
     */
    private fun updatePlaybackPosition() {
        if (!isActive) return

        val state = controller.playbackState
        val newPosition = state.elapsedPosition.milliseconds
        if (playbackPosition.value != newPosition) {
            playbackPosition.value = newPosition
        }

        if (state.state == PlaybackStateCompat.STATE_PLAYING) {
            val delay = (1.0 / playbackPositionRefreshRate).seconds
            handler.postDelayed(::updatePlaybackPosition,  delay.toLongMilliseconds())
        }
    }

    // MediaControllerCompat.Callback

    private inner class MediaControllerCallback : MediaControllerCompat.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (!isActive || metadata?.id == null) return

            mediaMetadata.value = metadata
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            if (!isActive) return

            playbackState.value = state
            if (state?.state == PlaybackState.STATE_PLAYING) {
                playWhenReady = false
                updatePlaybackPosition()
            }
        }

        override fun onSessionEvent(event: String?, extras: Bundle?) {
            super.onSessionEvent(event, extras)

            if (event == MediaService.EVENT_PUBLICATION_CHANGED && extras?.getString(MediaService.EXTRA_PUBLICATION_ID) == publicationId && playWhenReady && needsPlaying) {
                play()
            }
        }

    }


    // Navigator

    private val _currentLocator = MutableStateFlow(Locator(href = "#", type = ""))
    override val currentLocator: StateFlow<Locator> get() = _currentLocator.asStateFlow()

    /**
     * Creates a [Locator] from the given media [metadata] and playback [position].
     */
    @Suppress("RedundantSuspendModifier")
    private suspend fun createLocator(position: Duration?, metadata: MediaMetadataCompat?): Locator? {
        val href = metadata?.resourceHref ?: return null
        val index = publication.readingOrder.indexOfFirstWithHref(href) ?: return null
        var locator = publication.readingOrder[index].toLocator()

        if (position != null) {
            val startPosition = durations.slice(0 until index).sum()
            val duration = durations[index]

            locator = locator.copyWithLocations(
                fragments = listOf("t=${position.inSeconds.roundToInt()}"),
                progression = duration?.let { position / duration },
                totalProgression = totalDuration?.let { (startPosition + position) / totalDuration }
            )
        }

        return locator
    }

    override fun go(locator: Locator, animated: Boolean, completion: () -> Unit): Boolean {
        if (!isActive) return false

        transportControls.playFromMediaId("$publicationId#${locator.href}", Bundle().apply {
            putParcelable("locator", locator)
        })
        completion()
        return true
    }

    override fun go(link: Link, animated: Boolean, completion: () -> Unit): Boolean =
        go(link.toLocator(), animated, completion)

    override fun goForward(animated: Boolean, completion: () -> Unit): Boolean {
        if (!isActive) return false

        seekRelative(skipForwardInterval)
        completion()
        return true
    }

    override fun goBackward(animated: Boolean, completion: () -> Unit): Boolean {
        if (!isActive) return false

        seekRelative(-skipBackwardInterval)
        completion()
        return true
    }


    // MediaNavigator

    override val playback: Flow<MediaPlayback> =
        combine(
            mediaMetadata.filterNotNull(),
            playbackState.filterNotNull(),
            playbackPosition.map { it.toLongMilliseconds() }
        ) { metadata, state, position ->
            // FIXME: Since upgrading to the latest flow version, there's a weird crash when combining a `Flow<Duration>`, like `playbackPosition`. Mapping it seems to do the trick.
            // See https://github.com/Kotlin/kotlinx.coroutines/issues/2353
            @Suppress("NAME_SHADOWING")
            val position = position.milliseconds

            val index = metadata.resourceHref?.let { publication.readingOrder.indexOfFirstWithHref(it) }
            if (index == null) {
                Timber.e("Can't find resource index in publication for media ID `${metadata.id}`.")
            }

            val duration = index?.let { durations[index] }

            MediaPlayback(
                state = state.toPlaybackState(),

                // FIXME: ExoPlayer's media session connector doesn't handle the playback speed yet, so I used a custom solution until we create our own connector
//                rate = state?.playbackSpeed?.toDouble() ?: 1.0,
                rate = player?.playbackRate ?: 1.0,

                timeline = MediaPlayback.Timeline(
                    position = position.coerceAtMost(duration ?: position),
                    duration = duration,
                    // Buffering is not yet supported, but will be with media2:
                    // https://developer.android.com/reference/androidx/media2/common/SessionPlayer#getBufferedPosition()
                    buffered = null
                )
            )
        }
        .distinctUntilChanged()
        .conflate()

    override val isPlaying: Boolean
        get() = playbackState.value?.isPlaying == true

    override fun setPlaybackRate(rate: Double) {
        if (!isActive) return
        // FIXME: ExoPlayer's media session connector doesn't handle the playback speed yet, so I used a custom solution until we create our own connector
//        transportControls.setPlaybackSpeed(rate.toFloat())
        player?.playbackRate = rate
    }

    override fun play() {
        if (!isActive) {
            playWhenReady = true
            return
        }
        transportControls.play()
    }

    override fun pause() {
        if (!isActive) return
        transportControls.pause()
    }

    override fun playPause() {
        if (!isActive) return

        if (controller.playbackState.isPlaying) {
            transportControls.pause()
        } else {
            transportControls.play()
        }
    }

    override fun stop() {
        if (!isActive) return
        transportControls.stop()
    }

    override fun seekTo(position: Duration) {
        if (!isActive) return

        @Suppress("NAME_SHADOWING")
        val position = position.coerceAtLeast(0.seconds)

        // We overwrite the current position to allow skipping successively several time without
        // having to wait for the playback position to actually update.
        playbackPosition.value = position

        transportControls.seekTo(position.toLongMilliseconds())
    }

    override fun seekRelative(offset: Duration) {
        if (!isActive) return

        seekTo(playbackPosition.value + offset)
    }

}
