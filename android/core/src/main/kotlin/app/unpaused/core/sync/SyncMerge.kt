package app.unpaused.core.sync

/**
 * Version stamp of one synced record (a playlist, a favourite, a setting). Records are never removed
 * outright; deleting writes a tombstone ([deleted] = true) so the deletion also reaches other devices.
 */
data class SyncStamp(
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val deviceId: String,
)

data class SyncPlan(
    /** Remote wins: write these remote records locally. */
    val pull: List<String>,
    /** Local wins: upload these local records. */
    val push: List<String>,
)

/**
 * Last-writer-wins merge. Ties on [SyncStamp.updatedAt] go to the tombstone (a delete beats an edit made
 * in the same millisecond), then to the larger device id so every device makes the same choice.
 * Mirrored by `web/src/lib/sync/merge.ts` and checked by `shared/test-vectors/sync-merge.json`.
 */
object SyncMerge {

    fun winner(a: SyncStamp, b: SyncStamp): SyncStamp {
        require(a.id == b.id) { "Comparing different records: ${a.id} vs ${b.id}" }
        return when {
            a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
            a.deleted != b.deleted -> if (a.deleted) a else b
            else -> if (a.deviceId >= b.deviceId) a else b
        }
    }

    fun plan(local: Collection<SyncStamp>, remote: Collection<SyncStamp>): SyncPlan {
        val l = local.associateBy { it.id }
        val r = remote.associateBy { it.id }
        val pull = mutableListOf<String>()
        val push = mutableListOf<String>()
        for (id in (l.keys + r.keys).sorted()) {
            val a = l[id]
            val b = r[id]
            when {
                a == null -> pull += id
                b == null -> push += id
                sameVersion(a, b) -> Unit
                winner(a, b) === b -> pull += id
                else -> push += id
            }
        }
        return SyncPlan(pull, push)
    }

    private fun sameVersion(a: SyncStamp, b: SyncStamp) =
        a.updatedAt == b.updatedAt && a.deleted == b.deleted && a.deviceId == b.deviceId
}
