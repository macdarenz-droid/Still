package app.still.gallery.media

import app.still.gallery.catalogue.CatalogueNamespace

/** A cleanup-specific fresh provider scope; never a durable or immutable byte snapshot. */
data class CleanupSourceSnapshot(
    val items: List<MediaItem>,
    val namespaces: List<CatalogueNamespace>,
    val legacyFavoriteMatches: Map<String, String>,
    val access: MediaAccess,
)
