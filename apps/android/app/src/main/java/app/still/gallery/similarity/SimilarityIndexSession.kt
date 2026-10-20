package app.still.gallery.similarity

import app.still.gallery.media.CleanupSourceSnapshot

/** Store and worker seams let the UI coordinator settle even if its queued worker never enters. */
interface SimilarityIndexStoreSession : SimilarityIndexRepository, AutoCloseable {
    fun count(): Int
    fun clear()
}

/** Recovery-only session that can clear a known index without reading damaged descriptors. */
interface SimilarityIndexClearSession : AutoCloseable {
    fun clear()
}

interface SimilarityIndexRunControl {
    fun invalidate()
    suspend fun run(
        onSnapshot: suspend (CleanupSourceSnapshot) -> Unit,
        onProgress: suspend (SimilarityIndexProgress) -> Unit,
    ): SimilarityIndexProgress
    suspend fun settle(): SimilaritySettleResult
}
