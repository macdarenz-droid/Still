package app.still.gallery.ui

import app.still.gallery.similarity.SimilarityIndexQueryCoverage

data class SimilarityIndexCoverageDisplay(
    val currentAuthorizedPhotos: Int,
    val indexedCurrentPhotos: Int,
    val indexedPhotosSearched: Int,
    val omittedWork: Boolean,
)

/** Coverage shown in the review screen counts distinct indexed photos, not pair checks. */
fun SimilarityIndexQueryCoverage.toDisplayCoverage() = SimilarityIndexCoverageDisplay(
    currentAuthorizedPhotos = currentAuthorizedPhotos,
    indexedCurrentPhotos = indexedCurrentPhotos,
    indexedPhotosSearched = indexedPhotosSearched,
    omittedWork = omittedWork,
)
