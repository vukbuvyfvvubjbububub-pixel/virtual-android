package com.virtualandroid.app

import com.virtualandroid.app.core.JobState
import com.virtualandroid.app.core.Phase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Çalışan indirme işlerinin anlık durumu (servis yazar, köprü okur). */
object Jobs {
    private val states = ConcurrentHashMap<String, JobState>()
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()

    private val activePhases = setOf(Phase.RESOLVING, Phase.DOWNLOADING, Phase.VERIFYING, Phase.EXTRACTING)

    fun state(id: String): JobState? = states[id]
    fun set(id: String, s: JobState) { states[id] = s }
    fun active(id: String): Boolean = states[id]?.phase in activePhases
    fun cancelFlag(id: String): AtomicBoolean = cancels.getOrPut(id) { AtomicBoolean(false) }
}
