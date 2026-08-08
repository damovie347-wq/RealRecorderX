package com.recorderx.app.service

/**
 * RecordingService and MainActivity run in the same process, so a plain
 * observable singleton is enough to keep the UI in sync -- no bound service,
 * no LiveData/Flow dependency, no broadcast. MainActivity adds/removes its
 * listener in onStart/onStop.
 */
object RecordingSessionState {

    enum class Phase { IDLE, RECORDING, PAUSED }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    @Volatile var elapsedMs: Long = 0L
        private set

    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /** Always called from the main thread by RecordingService, so listeners
     * (which touch views) never need to hop threads themselves. */
    fun update(newPhase: Phase, newElapsedMs: Long = elapsedMs) {
        phase = newPhase
        elapsedMs = newElapsedMs
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }
}
