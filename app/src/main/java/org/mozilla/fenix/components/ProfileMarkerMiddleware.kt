/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.concept.base.profiler.Profiler
import mozilla.components.lib.state.Action
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.State
import mozilla.components.lib.state.Store

/**
 * A generic middleware that can be attached to a Store to log every action that is processed by that store. The
 * standard message format will use [markerName] as a qualifier and the action name as a detail message.
 */
class ProfileMarkerMiddleware<S : State, A : Action>(
    val markerName: String = "Unspecified",
    val profiler: Profiler?,
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : Middleware<S, A> {
    override fun invoke(
        store: Store<S, A>,
        next: (A) -> Unit,
        action: A,
    ) {
        next(action)
        // The Gecko backend checks an atomic active flag. Avoid allocating a coroutine and
        // queueing main-thread work for every store action when no profile is being recorded.
        if (profiler?.isProfilerActive() != true) return
        scope.launch {
            // Store actions can arrive on any thread; Java marker recording still needs Main.
            profiler.addMarker(markerName = markerName, text = action::class.java.name)
        }
    }
}
