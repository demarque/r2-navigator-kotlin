/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.util

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory

/**
 * A simple [FragmentFactory] creating a single [Fragment] type.
 */
abstract class SingleFragmentFactory<T : Fragment> : FragmentFactory() {

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        val fragment = instantiate()
        val fragmentClass = fragment::class.java

        return when (className) {
            fragmentClass.name -> fragment
            else -> super.instantiate(classLoader, className)
        }
    }

    abstract fun instantiate(): T

}
