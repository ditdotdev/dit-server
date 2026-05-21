package com.datadatdat.orchestrator

import com.datadatdat.ServiceLocator
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock

/*
 * The reaper orchestrator is the class that asynchronously looks for objects in the DELETING state that can be
 * safely deleted, invoking the appropriate storage provider method to do so. The reaper runs asynchronously in
 * a separate thread, and is poked whwnever an object is marked as deleting, and then continues to run as long
 * as there are objects to delete.
 *
 * It respects the ZFS dependency chain between commits and volumesets, ensuring that they're deleted
 * in that order. It will ensure that any clones of a commit are deleted before the commit itself is deleted.
 */
class Reaper(
    val services: ServiceLocator,
) : Runnable {
    private val lock = ReentrantLock()
    private val cv = lock.newCondition()

    companion object {
        val log = LoggerFactory.getLogger(Reaper::class.java)
    }

    // Signaled work pending. Read under the lock by the reaper thread,
    // set under the lock by any other thread calling signal(). A simple
    // boolean is sufficient — multiple signals while a reap is in flight
    // collapse to "do one more iteration after this one finishes".
    private var pending = false

    fun signal() {
        lock.lock()
        try {
            pending = true
            cv.signal()
        } finally {
            lock.unlock()
        }
    }

    override fun run() {
        // Previously the lock was held for the ENTIRE body of run() (the
        // condvar wait + all subsequent reap work, which includes
        // shell-out ZFS calls that can take seconds). Any concurrent
        // signal() from a request handler (deleteVolume, deleteCommit,
        // …) would block on lock.lock() until the in-flight reap
        // finished, adding ZFS latency directly to user-facing DELETE
        // requests. Now we only hold the lock around the condvar
        // wait/signal — the reap body runs without it, so signals are
        // never blocked by ongoing work.
        //
        // Loop semantics match the old code: keep iterating while any
        // reapXxx made progress (deleting one commit can unblock others
        // via the no-clones constraint); only wait on the condvar when a
        // full pass produced no changes.
        var changed = true
        while (true) {
            if (!changed) {
                lock.lock()
                try {
                    while (!pending) {
                        cv.await()
                    }
                    pending = false
                } finally {
                    lock.unlock()
                }
            }

            log.debug("reaping storage objects")

            // Do the reap pass without holding the lock. A signal arriving
            // mid-reap sets pending=true and the next iteration will pick
            // it up. If multiple signals arrive during the reap, they
            // collapse into one extra iteration (still correct — the
            // reapXxx methods re-query the metadata each pass).
            changed = false
            try {
                changed = reapCommits() || changed
            } catch (e: Throwable) {
                log.error("error during reapCommits", e)
            }
            try {
                markEmptyVolumeSets()
            } catch (e: Throwable) {
                log.error("error during markEmptyVolumeSets", e)
            }
            try {
                changed = reapVolumes() || changed
            } catch (e: Throwable) {
                log.error("error during reapVolumes", e)
            }
            try {
                changed = reapVolumeSets() || changed
            } catch (e: Throwable) {
                log.error("error during reapVolumeSets", e)
            }
        }
    }

    /*
     * When reaping commits, we look for any that have been marked deleting and do not have any clones.
     */
    fun reapCommits(): Boolean {
        val commits =
            transaction {
                services.metadata.listDeletingCommits().filter {
                    !services.metadata.hasClones(it)
                }
            }

        var ret = false
        for (c in commits) {
            val volumes =
                transaction {
                    services.metadata.listVolumes(c.volumeSet)
                }
            try {
                services.context.deleteVolumeSetCommit(c.volumeSet, c.guid)
                for (v in volumes) {
                    services.context.deleteVolumeCommit(c.volumeSet, c.guid, v.name)
                }
                transaction {
                    services.metadata.deleteCommit(c)
                }
                ret = true
            } catch (e: Throwable) {
                log.error("error deleting commit ${c.guid}", e)
            }
        }

        return ret
    }

    fun markEmptyVolumeSets() {
        val volumeSets =
            transaction {
                services.metadata.listInactiveVolumeSets().filter {
                    services.metadata.isVolumeSetEmpty(it) &&
                        !services.metadata.operationRunning(it)
                }
            }

        transaction {
            for (vs in volumeSets) {
                services.metadata.markVolumeSetDeleting(vs)
            }
        }
    }

    fun reapVolumeSets(): Boolean {
        val volumeSets =
            transaction {
                services.metadata.listDeletingVolumeSets().filter {
                    services.metadata.isVolumeSetEmpty(it)
                }
            }

        var ret = false
        for (vs in volumeSets) {
            try {
                val volumes =
                    transaction {
                        services.metadata.listVolumes(vs)
                    }
                for (vol in volumes) {
                    services.context.deleteVolume(vs, vol.name, vol.config)
                    transaction {
                        services.metadata.deleteVolume(vs, vol.name)
                    }
                }
                services.context.deleteVolumeSet(vs)
                transaction {
                    services.metadata.deleteVolumeSet(vs)
                }
                ret = true
            } catch (t: Throwable) {
                log.error("error reaping volume set $vs", t)
            }
        }

        return ret
    }

    // We don't recursively mark volumes deleted, this is only true when volumes are explicitly deleted
    fun reapVolumes(): Boolean {
        val volumes =
            transaction {
                services.metadata.listDeletingVolumes()
            }
        var ret = false
        for ((vs, vol) in volumes) {
            try {
                services.context.deleteVolume(vs, vol.name, vol.config)
                transaction {
                    services.metadata.deleteVolume(vs, vol.name)
                }
                ret = true
            } catch (t: Throwable) {
                log.error("error reaping volume ${vol.name} in volume set $vs")
            }
        }
        return ret
    }
}
