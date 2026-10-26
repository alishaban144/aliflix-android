package com.aliflix.app.talk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounds an unanswered speech turn. Progress re-arms it; real output cancels it.
 * Ownership and generation checks prevent an old timer expiring a newer turn. */
internal class TalkResponseDeadline(
    private val scope:CoroutineScope,
    private val timeoutMillis:Long=12000,
    private val onTimeout:()->Unit,
) {
    private var generation=0L
    private var job:Job?=null
    internal val pending:Boolean get()=synchronized(this){job?.isActive==true}
    @Synchronized fun awaiting(timeout:Long=timeoutMillis) {
        val ticket=++generation
        job?.cancel()
        job=scope.launch {
            delay(timeout)
            synchronized(this@TalkResponseDeadline) {if(ticket==generation)onTimeout()}
        }
    }
    @Synchronized fun clear() {generation++;job?.cancel();job=null}
}
