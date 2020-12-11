/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Mostapha Idoubihi
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.navigator.pager

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.R
import org.readium.r2.shared.SCROLL_REF
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import kotlin.coroutines.CoroutineContext


class R2CbzPageFragment(private val publication: Publication)
    : androidx.fragment.app.Fragment(), CoroutineScope  {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main

    private val link: Link
        get() = requireArguments().getParcelable("link")!!

    private var windowInsets: WindowInsetsCompat = WindowInsetsCompat.CONSUMED
    private lateinit var containerView: View
    private lateinit var imageView: ImageView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        containerView = inflater.inflate(R.layout.viewpager_fragment_cbz, container, false)
        imageView = containerView.findViewById(R.id.imageView)

        setupPadding()

       launch {
           publication.get(link)
               .read()
               .getOrNull()
               ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
               ?.let { imageView.setImageBitmap(it) }
       }

       return containerView
    }

    private fun setupPadding() {
        updatePadding()

        // Update padding when the window insets change, for example when the navigation and status
        // bars are toggled.
        ViewCompat.setOnApplyWindowInsetsListener(containerView) { _, insets ->
            windowInsets = insets
            updatePadding()
            insets
        }
    }

    private fun updatePadding() {
        val activity = activity ?: return
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            return
        }

        var top = 0
        var bottom = 0

        // Add additional padding to take into account the display cutout, if needed.
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
            activity.window.attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        ) {
            val displayCutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            top += displayCutoutInsets.top
            bottom += displayCutoutInsets.bottom
        }

        imageView.setPadding(0, top, 0, bottom)
    }

}


