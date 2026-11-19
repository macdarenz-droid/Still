package app.still.gallery.similarity

import app.still.gallery.media.MediaItem
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assert.*
import org.junit.Test

/** Synthetic JVM retrieval evidence; the fixture index below does not execute Android SQLite. */
class SimilarityIndexRetrievalEvaluationTest {
    private enum class Label { SAME_APPEARANCE, DIFFERENT_CONTENT }
    private data class Pixels(val width: Int, val height: Int, val values: IntArray, val orientation: Int = 1)
    private data class Case(val id: String, val query: Pixels, val target: Pixels, val label: Label)

    // BEGIN FROZEN S42 V3 CORPUS
    private val corpusSeed = 0xC012730B5L
    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (255 shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
    private fun pixels(width: Int = 192, height: Int = 144, color: (Int, Int) -> Int): Pixels =
        Pixels(width, height, IntArray(width * height) { index -> color(index % width, index / width) })
    private fun paint(image: Pixels, color: Int, inside: (Int, Int) -> Boolean) {
        for (y in 0 until image.height) for (x in 0 until image.width) if (inside(x, y)) image.values[y * image.width + x] = color
    }
    private fun rectangle(image: Pixels, left: Int, top: Int, right: Int, bottom: Int, color: Int) =
        paint(image, color) { x, y -> x in left until right && y in top until bottom }

    private fun tabletop(seed: Long, fullCup: Boolean = true): Pixels {
        val random = Random(seed)
        val image = pixels { x, y ->
            val grain = if ((x + y * 2) % 23 < 3) 9 else 0
            rgb(119 + x / 8 + grain, 82 + y / 9 + grain, 55 + x / 13 + grain)
        }
        val bookX = 12 + random.nextInt(13)
        val bookY = 10 + random.nextInt(9)
        rectangle(image, bookX + 4, bookY + 4, bookX + 69, bookY + 94, rgb(43, 46, 51))
        rectangle(image, bookX, bookY, bookX + 65, bookY + 90, rgb(43, 91, 139))
        rectangle(image, bookX + 8, bookY + 10, bookX + 57, bookY + 18, rgb(211, 194, 149))
        rectangle(image, bookX + 12, bookY + 33, bookX + 53, bookY + 36, rgb(183, 191, 198))
        rectangle(image, bookX + 12, bookY + 42, bookX + 45, bookY + 45, rgb(183, 191, 198))
        val cupX = 132 + random.nextInt(19)
        val cupY = 40 + random.nextInt(15)
        paint(image, rgb(212, 210, 199)) { x, y -> (x - cupX - 22) * (x - cupX - 22) + (y - cupY) * (y - cupY) < 140 }
        paint(image, rgb(74, 63, 57)) { x, y -> (x - cupX - 22) * (x - cupX - 22) + (y - cupY) * (y - cupY) < 55 }
        paint(image, rgb(222, 218, 202)) { x, y -> (x - cupX) * (x - cupX) + (y - cupY) * (y - cupY) < 700 }
        paint(image, if (fullCup) rgb(48, 34, 29) else rgb(191, 184, 165)) { x, y ->
            (x - cupX) * (x - cupX) + (y - cupY) * (y - cupY) < 410
        }
        val pencilY = 108 + random.nextInt(8)
        paint(image, rgb(177, 48, 42)) { x, y -> x in 78..175 && kotlin.math.abs(y - pencilY - (x - 78) / 9) < 3 }
        rectangle(image, 16, 117, 66, 133, rgb(199, 177, 115))
        repeat(9) { tick -> rectangle(image, 19 + tick * 5, 117, 20 + tick * 5, 123, rgb(61, 57, 49)) }
        return image
    }

    private val numeral = listOf(
        "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
        "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111",
    )
    private fun receipt(seed: Long, digit: Int, page: Int): Pixels {
        val random = Random(seed)
        val image = pixels { _, _ -> rgb(225, 224, 210) }
        rectangle(image, 8, 7, 184, 19, rgb(47, 77, 98))
        repeat(10) { row ->
            val left = if (row % 3 == 0) 20 else 12
            val width = 104 + random.nextInt(41) - page * (row % 4) * 9
            rectangle(image, left, 27 + row * 10, left + width, 29 + row * 10, rgb(54, 58, 63))
        }
        rectangle(image, 146, 23, 149, 134, rgb(102, 103, 100))
        for (y in 0..4) for (x in 0..2) if (numeral[digit][y * 3 + x] == '1') {
            rectangle(image, 159 + x * 6, 87 + y * 6, 165 + x * 6, 93 + y * 6, rgb(31, 44, 53))
        }
        return image
    }

    private fun screenState(seed: Long, enabled: Boolean): Pixels {
        val image = tabletop(seed)
        rectangle(image, 87, 82, 138, 133, rgb(37, 40, 47))
        rectangle(image, 92, 88, 133, 123, rgb(159, 182, 188))
        rectangle(image, 101, 101, if (enabled) 127 else 111, 112, if (enabled) rgb(48, 143, 79) else rgb(171, 67, 55))
        return image
    }

    private fun light(source: Pixels, amount: Int): Pixels = Pixels(source.width, source.height, source.values.map { color ->
        rgb(((color ushr 16) and 255) + amount, ((color ushr 8) and 255) + amount, (color and 255) + amount)
    }.toIntArray())
    private fun resize(source: Pixels, width: Int, height: Int): Pixels = pixels(width, height) { x, y ->
        source.values[(y.toLong() * source.height / height).toInt() * source.width + (x.toLong() * source.width / width).toInt()]
    }
    private fun crop(source: Pixels, amount: Int): Pixels = pixels(source.width - amount * 2, source.height - amount * 2) { x, y ->
        source.values[(y + amount) * source.width + x + amount]
    }
    private fun tint(source: Pixels, red: Boolean): Pixels = Pixels(source.width, source.height, source.values.map { color ->
        val level = (((color ushr 16) and 255) + ((color ushr 8) and 255) + (color and 255)) / 3
        if (red) rgb(level + 53, level / 4, level / 3) else rgb(level / 3, level / 4, level + 53)
    }.toIntArray())
    private fun translucent(source: Pixels, hiddenRed: Boolean): Pixels = pixels(source.width, source.height) { x, y ->
        if (x < 17 && y < 13) (if (hiddenRed) 0x00FF0000 else 0x000000FF)
        else (190 shl 24) or (source.values[y * source.width + x] and 0x00FFFFFF)
    }

    private fun orient(source: Pixels, orientation: Int): Pixels {
        val width = if (orientation >= 5) source.height else source.width
        val height = if (orientation >= 5) source.width else source.height
        val result = IntArray(width * height)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val (dx, dy) = when (orientation) {
                1 -> x to y
                2 -> source.width - 1 - x to y
                3 -> source.width - 1 - x to source.height - 1 - y
                4 -> x to source.height - 1 - y
                5 -> y to x
                6 -> source.height - 1 - y to x
                7 -> source.height - 1 - y to source.width - 1 - x
                8 -> y to source.width - 1 - x
                else -> error("Invalid fixture orientation")
            }
            result[dy * width + dx] = source.values[y * source.width + x]
        }
        return Pixels(width, height, result)
    }
    private fun withExif(source: Pixels, orientation: Int): Pixels =
        orient(source, when (orientation) { 6 -> 8; 8 -> 6; else -> orientation }).copy(orientation = orientation)

    private fun jpeg(source: Pixels, quality: Float): Pixels {
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, source.width, source.height, source.values, 0, source.width)
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(bytes).use { output ->
                writer.output = output
                val options = writer.defaultWriteParam
                options.compressionMode = ImageWriteParam.MODE_EXPLICIT
                options.compressionQuality = quality
                writer.write(null, IIOImage(image, null, null), options)
            }
        } finally { writer.dispose() }
        val decoded = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes.toByteArray())))
        return Pixels(decoded.width, decoded.height, decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width))
    }

    private fun corpus(): List<Case> {
        val first = tabletop(corpusSeed + 9301)
        val second = tabletop(corpusSeed + 9302)
        val third = tabletop(corpusSeed + 9303)
        val fourth = tabletop(corpusSeed + 9304)
        val fifth = tabletop(corpusSeed + 9305)
        val page = receipt(corpusSeed + 9306, 1, 0)
        return listOf(
            Case("v3-light", first, light(first, 16), Label.SAME_APPEARANCE),
            Case("v3-dark", second, light(second, -11), Label.SAME_APPEARANCE),
            Case("v3-resize", third, resize(third, 256, 192), Label.SAME_APPEARANCE),
            Case("v3-jpeg", fourth, jpeg(fourth, 0.49f), Label.SAME_APPEARANCE),
            Case("v3-crop", first, crop(first, 6), Label.SAME_APPEARANCE),
            Case("v3-subject", first, page, Label.DIFFERENT_CONTENT),
            Case("v3-document-digit", page, receipt(corpusSeed + 9306, 7, 0), Label.DIFFERENT_CONTENT),
            Case("v3-document-page", page, receipt(corpusSeed + 9306, 1, 1), Label.DIFFERENT_CONTENT),
            Case("v3-screen-state", screenState(corpusSeed + 9307, false), screenState(corpusSeed + 9307, true), Label.DIFFERENT_CONTENT),
            Case("v3-empty-full", tabletop(corpusSeed + 9308, false), tabletop(corpusSeed + 9308, true), Label.DIFFERENT_CONTENT),
            Case("v3-color", tint(second, true), tint(second, false), Label.DIFFERENT_CONTENT),
            Case("v3-aspect", third, resize(third, 64, 224), Label.DIFFERENT_CONTENT),
            Case("v3-pattern", pixels { x, y -> if ((x + y * 2) / 13 % 2 == 0) rgb(42, 89, 128) else rgb(201, 178, 119) },
                pixels { x, y -> if ((x / 13 + y / 13) % 2 == 0) rgb(42, 89, 128) else rgb(201, 178, 119) }, Label.DIFFERENT_CONTENT),
            Case("v3-alpha", translucent(fifth, true), translucent(fifth, false), Label.SAME_APPEARANCE),
        ) + (1..8).map { orientation -> Case("v3-exif-$orientation", fifth, withExif(fifth, orientation), Label.SAME_APPEARANCE) }
    }
    // END FROZEN S42 V3 CORPUS

    private val incarnation = "b".repeat(64)
    private val namespaces = mapOf(similarityIndexNamespaceKey("media", "external_primary") to incarnation)
    private fun item(number: Int, year: Int = 2026) = MediaItem(
        "content://media/external_primary/images/media/$number", "fixture-$number.jpg", "image/jpeg", false,
        java.time.LocalDate.of(year, 1, 1).toEpochDay() * 86_400_000, 192, 144, 1024, 0, "album", "Fixture",
        modifiedAt = number.toLong(), providerAuthority = "media", volumeName = "external_primary",
        providerVersion = "fixture-v3", providerGeneration = 90, externalHandle = number.toString(),
        generationAdded = number.toLong(), generationModified = number.toLong(), dateAddedSeconds = number.toLong(),
    )
    private fun feature(image: Pixels): SimilarityPixelResult = similarityFeature(image.width, image.height, image.values, image.orientation)
    private fun informative(image: Pixels): SimilarityFeature = (feature(image) as SimilarityPixelResult.Accepted).feature
    private fun baseFeature() = informative(tabletop(corpusSeed + 9301))
    private fun analyzed(item: MediaItem, feature: SimilarityFeature) = SimilarityAnalyzedSource(item, feature, "e".repeat(64), 1024)
    private fun identity(item: MediaItem) = requireNotNull(similarityIndexIdentity(item, incarnation))

    private data class Stored(val key: String, val revision: String, val hash: Long, val descriptor: ByteArray)
    private fun stored(item: MediaItem, feature: SimilarityFeature, descriptor: ByteArray = SimilarityFeatureCodec.encode(feature)): Stored {
        val identity = identity(item)
        return Stored(identity.sourceKey, identity.sourceRevision, feature.perceptualHash, descriptor.copyOf())
    }

    /** Test storage boundary only: production codec/buckets and production search do the work. */
    private class FixtureIndex(
        private val rows: List<Stored>, private val candidateCap: Int = 100_000,
        private val onCandidates: () -> Unit = {},
    ) : SimilarityFeatureIndex {
        val descriptorLoadSizes = mutableListOf<Int>()
        override fun revisions(): Map<String, String> = rows.associate { it.key to it.revision }
        override fun candidates(feature: SimilarityFeature, maximum: Int): SimilarityIndexCandidates {
            onCandidates()
            val blocks = similarityHashBuckets(feature.perceptualHash)
            val keys = rows.filter { row ->
                val other = similarityHashBuckets(row.hash)
                blocks.indices.any { blocks[it] == other[it] }
            }.map(Stored::key).distinct().sorted()
            val limit = minOf(maximum, candidateCap)
            return SimilarityIndexCandidates(keys.take(limit), keys.size > limit)
        }
        override fun features(sourceRevisions: Map<String, String>, sourceKeys: List<String>): Map<String, SimilarityFeature> {
            descriptorLoadSizes += sourceKeys.size
            return rows.filter { it.key in sourceKeys && sourceRevisions[it.key] == it.revision }
                .associate { it.key to requireNotNull(SimilarityFeatureCodec.decode(it.descriptor)) }
        }
    }

    @Test fun unseenV3CorpusRunsThroughProductionIndexRetrievalWithoutThresholdTuning() {
        val cases = corpus()
        assertEquals(22, cases.size)
        assertEquals(14, cases.count { it.label == Label.SAME_APPEARANCE })
        assertEquals(8, cases.count { it.label == Label.DIFFERENT_CONTENT })
        val falsePositives = mutableListOf<String>()
        val misses = mutableListOf<String>()
        val retrieved = mutableMapOf<String, Boolean>()
        for ((number, case) in cases.withIndex()) {
            val queryItem = item(10_000 + number * 3, 1999)
            val skippedSelection = item(10_001 + number * 3)
            val target = item(10_002 + number * 3)
            val left = feature(case.query)
            val right = feature(case.target)
            val query = (left as? SimilarityPixelResult.Accepted)?.feature
            val targetFeature = (right as? SimilarityPixelResult.Accepted)?.feature
            val rows = if (targetFeature == null) emptyList() else listOf(stored(target, targetFeature))
            val index = FixtureIndex(rows)
            val result = searchSimilarityIndex(
                if (query == null) emptyList() else listOf(analyzed(queryItem, query)),
                listOf(queryItem, skippedSelection), listOf(queryItem, skippedSelection, target), index, namespaces,
            )
            val found = result.additionalPairs.any { it.first.id == queryItem.id && it.second.id == target.id }
            retrieved[case.id] = found
            val decoded = rows.singleOrNull()?.let { requireNotNull(SimilarityFeatureCodec.decode(it.descriptor)) }
            val oracle = query != null && decoded != null && similarityDistance(query, decoded) != null
            if (oracle) assertTrue("No retrieval loss for eligible v3 pair ${case.id} without omitted work", found)
            if (case.label == Label.DIFFERENT_CONTENT && found) falsePositives += case.id
            if (case.label == Label.SAME_APPEARANCE && !found) misses += case.id
            fun status(result: SimilarityPixelResult) = when (result) {
                is SimilarityPixelResult.Accepted -> result.feature.algorithmVersion
                is SimilarityPixelResult.Rejected -> "rejected:${result.reason}"
            }
            println("S42_RETRIEVAL|${case.id}|${case.label}|retrieved=$found|query=${status(left)}|target=${status(right)}|eligibleAfterCodec=$oracle|pairChecks=${result.coverage.pairChecks}")
            assertFalse(result.coverage.omittedWork)
            assertTrue(result.coverage.pairChecks in 0..1)
            assertTrue(result.additionalPairs.all { it.reviewOnly })
        }
        println("S42_SUMMARY|tabletop-retrieval-v3|pairs=${cases.size}|candidateFalsePositives=$falsePositives|misses=$misses")
        for (id in listOf("v3-light", "v3-dark", "v3-resize", "v3-jpeg") + (1..8).map { "v3-exif-$it" }) {
            assertTrue("Useful unseen retrieval positive $id", retrieved.getValue(id))
        }
        for (id in listOf("v3-subject", "v3-color", "v3-aspect")) assertFalse(id, retrieved.getValue(id))
    }

    @Test fun encodedDescriptorFileRoundTripKeepsCrossYearRetrievalAndCurrentDisplayMetadata() {
        val query = item(1, 1999); val anchor = item(2); val target = item(3)
        val queryFeature = baseFeature()
        val targetFeature = informative(jpeg(tabletop(corpusSeed + 9301), 0.49f))
        val encoded = SimilarityFeatureCodec.encode(targetFeature)
        val path = Files.createTempFile("still-s42-descriptor-", ".bin")
        val persisted = try { Files.write(path, encoded); Files.readAllBytes(path) } finally { Files.deleteIfExists(path) }
        assertArrayEquals(encoded, persisted)
        assertEquals(SimilarityFeatureCodec.ENCODED_BYTES, persisted.size)
        val currentTarget = target.copy(name = "current-name.jpg", capturedAt = target.capturedAt + 86_400_000)
        val result = searchSimilarityIndex(listOf(analyzed(query, queryFeature)), listOf(query, anchor),
            listOf(query, anchor, currentTarget), FixtureIndex(listOf(stored(target, targetFeature, persisted))), namespaces)
        assertEquals(1, result.additionalPairs.size)
        assertEquals("current-name.jpg", result.additionalPairs.single().second.name)
        assertEquals(currentTarget.capturedAt, result.additionalPairs.single().second.capturedAt)
        assertTrue(result.additionalPairs.single().reviewOnly)
    }

    @Test fun changedRevisionWithdrawnSourceAndNamespaceRotationCannotReuseStoredFeatures() {
        val query = item(11); val anchor = item(12); val target = item(13)
        val descriptor = baseFeature()
        val index = FixtureIndex(listOf(stored(target, descriptor)))
        val selected = listOf(analyzed(query, descriptor))
        val changed = target.copy(generationModified = requireNotNull(target.generationModified) + 1)
        val revisionMiss = searchSimilarityIndex(selected, listOf(query, anchor), listOf(query, anchor, changed), index, namespaces)
        val withdrawn = searchSimilarityIndex(selected, listOf(query, anchor), listOf(query, anchor), index, namespaces)
        val rotated = searchSimilarityIndex(selected, listOf(query, anchor), listOf(query, anchor, target), index,
            mapOf(similarityIndexNamespaceKey("media", "external_primary") to "c".repeat(64)))
        for (result in listOf(revisionMiss, withdrawn, rotated)) {
            assertTrue(result.additionalPairs.isEmpty())
            assertEquals(0, result.coverage.indexedCurrentPhotos)
            assertEquals(0, result.coverage.pairChecks)
        }
        assertTrue(index.descriptorLoadSizes.isEmpty())
    }

    @Test fun hammingRadiusTenCandidatesSurviveTheProductionBucketAndQueryPath() {
        val query = item(21); val anchor = item(22)
        val descriptor = baseFeature()
        val positions = listOf(1, 6, 12, 18, 24, 30, 36, 42, 48, 54, 59)
        val targets = mutableListOf<MediaItem>()
        val rows = mutableListOf<Stored>()
        for (rotation in 0..2) for (radius in 0..10) {
            val target = item(100 + targets.size)
            var hash = descriptor.perceptualHash
            repeat(radius) { bit -> hash = hash xor (1L shl positions[(bit + rotation) % positions.size]) }
            val candidate = SimilarityFeature(hash, descriptor.aspectRatio, descriptor.spatialValues(), descriptor.histogramValues())
            targets += target; rows += stored(target, candidate)
        }
        val result = searchSimilarityIndex(listOf(analyzed(query, descriptor)), listOf(query, anchor),
            listOf(query, anchor) + targets, FixtureIndex(rows), namespaces)
        assertEquals(targets.map(MediaItem::id).toSet(), result.additionalPairs.map { it.second.id }.toSet())
        assertEquals(33, result.coverage.pairChecks)
        assertFalse(result.coverage.omittedWork)
    }

    @Test fun uniqueIndexedPhotoCoverageIsSeparateFromRepeatedPairComparisons() {
        val selected = listOf(item(31), item(32))
        val targets = (33..39).map { item(it) }
        val descriptor = baseFeature()
        val result = searchSimilarityIndex(selected.map { analyzed(it, descriptor) }, selected, selected + targets,
            FixtureIndex(targets.map { stored(it, descriptor) }), namespaces)
        assertEquals(14, result.coverage.pairChecks)
        assertEquals(7, result.coverage.indexedCurrentPhotos)
        assertEquals(7, result.coverage.indexedPhotosSearched)
    }

    @Test fun comparisonBudgetAndResultCapReportDifferentKindsOfOmittedWork() {
        val selected = listOf(item(41), item(42))
        val targets = (43..72).map { item(it) }
        val descriptor = baseFeature()
        val index = FixtureIndex(targets.map { stored(it, descriptor) })
        val limited = searchSimilarityIndex(selected.map { analyzed(it, descriptor) }, selected, selected + targets,
            index, namespaces, maximumPairChecks = 7, maximumResults = 5)
        assertEquals(7, limited.coverage.pairChecks)
        assertTrue(limited.coverage.omittedWork)
        assertEquals(5, limited.additionalPairs.size)
        assertEquals(2, limited.coverage.additionalPairsOmitted)
        val complete = searchSimilarityIndex(selected.map { analyzed(it, descriptor) }, selected, selected + targets,
            index, namespaces, maximumResults = 5)
        assertEquals(60, complete.coverage.pairChecks)
        assertFalse(complete.coverage.omittedWork)
        assertEquals(5, complete.additionalPairs.size)
        assertEquals(55, complete.coverage.additionalPairsOmitted)
        assertTrue(index.descriptorLoadSizes.all { it <= 128 })
    }

    @Test fun boundedCandidateLookupMarksUnknownRemainingCoverageExplicitly() {
        val query = item(81); val anchor = item(82)
        val targets = (83..90).map { item(it) }
        val descriptor = baseFeature()
        val result = searchSimilarityIndex(listOf(analyzed(query, descriptor)), listOf(query, anchor),
            listOf(query, anchor) + targets, FixtureIndex(targets.map { stored(it, descriptor) }, candidateCap = 1), namespaces)
        assertEquals(1, result.coverage.pairChecks)
        assertEquals(1, result.additionalPairs.size)
        assertTrue(result.coverage.omittedWork)
        assertEquals(8, result.coverage.indexedCurrentPhotos)
    }

    @Test fun authorityLossDuringEmptyCandidateLookupCannotPublishCurrentCoverage() {
        val query = item(91); val anchor = item(92)
        var authorized = true
        val index = FixtureIndex(emptyList(), onCandidates = { authorized = false })
        try {
            searchSimilarityIndex(listOf(analyzed(query, baseFeature())), listOf(query, anchor), listOf(query, anchor),
                index, namespaces, checkCurrent = { check(authorized) })
            fail("Withdrawn authority must fence even empty index results")
        } catch (_: IllegalStateException) { }
    }

    @Test fun selectedSourceWhoseReadWasSkippedIsStillExcludedFromIndexedResults() {
        val query = item(201); val skipped = item(202); val target = item(203)
        val descriptor = baseFeature()
        val result = searchSimilarityIndex(listOf(analyzed(query, descriptor)), listOf(query, skipped),
            listOf(query, skipped, target), FixtureIndex(listOf(stored(skipped, descriptor), stored(target, descriptor))), namespaces)
        assertEquals(listOf(target.id), result.additionalPairs.map { it.second.id })
        assertTrue(result.additionalPairs.all { it.reviewOnly })
    }
}
