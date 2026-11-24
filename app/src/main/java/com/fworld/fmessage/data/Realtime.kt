package com.fworld.fmessage.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

data class RtEvent(val entity: String, val type: String, val data: Rec, val id: String)

/** "Realtime" on top of a git repo: adaptive polling with ETag (304 responses are free). */
object Realtime {
    val events = MutableSharedFlow<RtEvent>(extraBufferCapacity = 512)

    @Volatile var openChatId: String? = null
    @Volatile var foreground = true
    @Volatile var fast = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val snap = HashMap<String, Map<String, Rec>>()
    private var startedAt = 0L

    @Synchronized
    fun start(@Suppress("UNUSED_PARAMETER") token: String) {
        stop()
        startedAt = System.currentTimeMillis()
        job = scope.launch {
            var tick = 0
            while (isActive) {
                try { pollOnce(tick++) } catch (e: CancellationException) { throw e } catch (ignored: Exception) {}
                delay(if (fast) 1000L else if (foreground) 2500L else 20_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        snap.clear()
    }

    private fun emit(entity: String, type: String, d: Rec) {
        events.tryEmit(RtEvent(entity, type, d, d.id))
    }

    /** Returns records that changed (created/updated) in this diff. */
    private fun diff(key: String, entity: String, now: List<Rec>, baselineBefore: Long = 0L): List<Rec> {
        val newMap = now.associateBy { it.id }
        val old = snap[key]
        snap[key] = newMap
        if (old == null) {
            if (baselineBefore > 0) newMap.values.filter { parseIso(it.s("created_date")) > baselineBefore }.forEach { emit(entity, "create", it) }
            return emptyList()
        }
        val changed = mutableListOf<Rec>()
        for ((id, r) in newMap) {
            val o = old[id]
            if (o == null) { emit(entity, "create", r); changed.add(r) }
            else if (o != r) { emit(entity, "update", r); changed.add(r) }
        }
        for ((id, o) in old) if (id !in newMap) emit(entity, "delete", o)
        return changed
    }

    private suspend fun pollOnce(tick: Int) {
        val changedChats = diff("Chat", "Chat", Gh.readArr("db/Chat.json"))
        if (tick % 4 == 0) diff("Profile", "Profile", Gh.readArr("db/Profile.json"))
        diff("Call", "Call", Gh.readArr("db/Call.json"))

        val toPoll = linkedSetOf<String>()
        openChatId?.let { toPoll.add(it) }
        changedChats.forEach { toPoll.add(it.id) }
        for (cid in toPoll) {
            val list = Gh.readArr("db/Message/$cid.json")
            diff("Message:$cid", "Message", list, baselineBefore = startedAt - 5_000)
        }
    }
}
