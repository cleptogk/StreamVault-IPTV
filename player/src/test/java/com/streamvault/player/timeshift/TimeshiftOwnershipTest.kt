package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TimeshiftOwnershipTest {

    @Test
    fun `session files are deleted only after capture cleanup joins`() = runBlocking {
        val events = mutableListOf<String>()
        val captureJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    events += "writer-closed"
                }
            }
        }

        stopOwnedTimeshiftCapture(activeCall = AtomicReference(null), captureJob = captureJob) {
            events += "files-deleted"
        }

        assertThat(events).containsExactly("writer-closed", "files-deleted").inOrder()
    }

    @Test
    fun `session shutdown waits for an in-flight snapshot before deleting files`() = runBlocking {
        val fence = TimeshiftSessionLifecycleFence()
        val snapshotStarted = CompletableDeferred<Unit>()
        val finishSnapshot = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val snapshotJob = launch {
            fence.withOpenSession {
                events += "snapshot-started"
                snapshotStarted.complete(Unit)
                finishSnapshot.await()
                events += "snapshot-written"
            }
        }
        snapshotStarted.await()

        val closeJob = launch {
            fence.close {
                events += "files-deleted"
            }
        }
        yield()

        assertThat(closeJob.isCompleted).isFalse()
        assertThat(events).containsExactly("snapshot-started")

        finishSnapshot.complete(Unit)
        joinAll(snapshotJob, closeJob)

        assertThat(events).containsExactly(
            "snapshot-started",
            "snapshot-written",
            "files-deleted"
        ).inOrder()
    }

    @Test
    fun `new snapshots are rejected after session shutdown begins`() = runBlocking {
        val fence = TimeshiftSessionLifecycleFence()
        fence.close { }

        var snapshotRan = false
        val result = fence.withOpenSession {
            snapshotRan = true
            "snapshot"
        }

        assertThat(snapshotRan).isFalse()
        assertThat(result).isNull()
    }
}
