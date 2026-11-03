/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.base.profiler.Profiler
import mozilla.components.lib.state.Action
import mozilla.components.lib.state.State
import mozilla.components.lib.state.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMarkerMiddlewareTest {

    private class QueueDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            pending.addLast(block)
        }

        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    @Test
    fun `inactive profiler does not queue main thread work and every action reaches reducer`() {
        verifyNoMarkerDispatches(
            FakeProfiler(active = false) { _, _ -> error("Unexpected marker") }
        )
    }

    @Test
    fun `missing profiler does not queue main thread work`() {
        verifyNoMarkerDispatches(null)
    }

    private fun verifyNoMarkerDispatches(profiler: Profiler?) {
        val dispatcher = QueueDispatcher()
        var reduced = 0
        val store =
            Store<SState, AAction>(
                SState,
                { state, _ ->
                    reduced++
                    state
                },
                listOf(
                    ProfileMarkerMiddleware(profiler = profiler, scope = CoroutineScope(dispatcher))
                ),
            )
        repeat(10_000) { store.dispatch(AAction()) }
        assertEquals(10_000, reduced)
        println(
            "10,000 store actions: ${dispatcher.dispatches} marker tasks queued (profiler inactive/missing)"
        )
        assertEquals(0, dispatcher.dispatches)
    }

    @Test
    fun `starting and stopping profiling takes effect without recreating middleware`() {
        val dispatcher = QueueDispatcher()
        val captured = mutableListOf<String>()
        val profiler = FakeProfiler(active = false) { name, _ -> captured.add(name) }
        val store =
            Store<SState, AAction>(
                SState,
                { state, _ -> state },
                listOf(
                    ProfileMarkerMiddleware(
                        markerName = "BrowserStore",
                        profiler = profiler,
                        scope = CoroutineScope(dispatcher),
                    )
                ),
            )
        store.dispatch(AAction())
        assertEquals(0, dispatcher.dispatches)
        profiler.active = true
        store.dispatch(AAction())
        assertEquals(1, dispatcher.dispatches)
        assertTrue("Marker must stay on the supplied main dispatcher", captured.isEmpty())
        dispatcher.drain()
        assertEquals(listOf("BrowserStore"), captured)
        profiler.active = false
        store.dispatch(AAction())
        assertEquals(1, dispatcher.dispatches)
    }

    data object SState : State

    data class AAction(val arg: String = "") : Action

    @OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
    @Test
    fun `profile marker middleware creates markers for every action dispatched to store`() =
        runTest {
            val captured = mutableListOf<Pair<String, String?>>()
            val profiler =
                FakeProfiler(
                    fakeAddMarker = { markerName, text ->
                        captured.add(markerName to text)
                    }
                )
            val markerName = "hi, my name is"
            val store =
                Store<SState, AAction>(
                    initialState = SState,
                    reducer = { state, _ -> state },
                    middleware =
                        listOf(
                            ProfileMarkerMiddleware(
                                markerName = markerName,
                                profiler = profiler,
                                scope = this,
                            )
                        ),
                )

            val actionMessages = listOf("one!", "two!", "buckle my shoe!")
            actionMessages.forEach { message ->
                store.dispatch(AAction(message))
                this.advanceUntilIdle()
                assertTrue(
                    captured.any { it.first == markerName && it.second!!.contains("AAction") }
                )
            }
        }

    private class FakeProfiler(
        var active: Boolean = true,
        var fakeAddMarker: (markerName: String, text: String?) -> Unit,
    ) : Profiler {
        override fun isProfilerActive(): Boolean {
            return active
        }

        override fun getProfilerTime(): Double? {
            TODO("Not yet implemented")
        }

        override fun addMarker(
            markerName: String,
            startTime: Double?,
            endTime: Double?,
            text: String?,
        ) {
            TODO("Not yet implemented")
        }

        override fun addMarker(
            markerName: String,
            startTime: Double?,
            text: String?,
        ) {
            TODO("Not yet implemented")
        }

        override fun addMarker(markerName: String, startTime: Double?) {
            TODO("Not yet implemented")
        }

        override fun addMarker(markerName: String, text: String?) {
            fakeAddMarker(markerName, text)
        }

        override fun addMarker(markerName: String) {
            TODO("Not yet implemented")
        }

        override fun startProfiler(
            filters: Array<String>,
            features: Array<String>,
        ) {
            TODO("Not yet implemented")
        }

        override fun stopProfiler(
            onSuccess: (ByteArray?) -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            TODO("Not yet implemented")
        }
    }
}
