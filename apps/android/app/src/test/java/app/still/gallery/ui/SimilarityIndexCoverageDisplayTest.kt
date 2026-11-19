package app.still.gallery.ui

import app.still.gallery.similarity.SimilarityIndexQueryCoverage
import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarityIndexCoverageDisplayTest {
    @Test fun reviewCoverageShowsDistinctIndexedPhotosRatherThanPairComparisons() {
        val backend = SimilarityIndexQueryCoverage(
            currentAuthorizedPhotos = 40,
            indexedCurrentPhotos = 30,
            indexedPhotosSearched = 30,
            pairChecks = 60,
            omittedWork = false,
            additionalPairsOmitted = 0,
        )

        val shown = backend.toDisplayCoverage()

        assertEquals(30, shown.indexedPhotosSearched)
        assertEquals(30, shown.indexedCurrentPhotos)
    }
}
