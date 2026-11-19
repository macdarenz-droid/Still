package app.still.gallery.similarity

import app.still.gallery.media.MediaItem
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assert.*
import org.junit.Test

class SimilarityEvaluationTest {
    // S36 fixture version 1. Seeds, generation and labels freeze before matcher observations.
    // Calibration is available for threshold selection. Held-out cases must not tune it.
    private enum class Label { SAME_SCENE, DIFFERENT_CONTENT }
    private data class Raster(val width: Int, val height: Int, val argb: IntArray, val orientation: Int = 1)
    private data class Case(val id: String, val first: Raster, val second: Raster, val label: Label)
    private data class RejectCase(val id: String, val image: Raster, val reason: String)
    private val calibrationSeed = 0x54A11L
    private val heldOutSeed = 0x11C0FFEEL

    // BEGIN FROZEN FIXTURES
    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (255 shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    private fun raster(width: Int = 160, height: Int = 112, pixel: (Int, Int) -> Int): Raster =
        Raster(width, height, IntArray(width * height) { index -> pixel(index % width, index / width) })

    private fun paint(image: Raster, inside: (Int, Int) -> Boolean, color: Int) {
        for (y in 0 until image.height) for (x in 0 until image.width) if (inside(x, y)) image.argb[y * image.width + x] = color
    }

    private fun rectangle(image: Raster, left: Int, top: Int, right: Int, bottom: Int, color: Int) =
        paint(image, { x, y -> x in left until right && y in top until bottom }, color)

    private fun scene(seed: Long): Raster {
        val random = Random(seed)
        val image = raster { x, y ->
            if (y < 67) rgb(62 + x / 4, 126 + y / 2, 190 + x / 5)
            else rgb(66 + x / 3, 123 - (y - 67) / 3, 44 + y / 5)
        }
        val sunX = 20 + random.nextInt(24)
        paint(image, { x, y -> (x - sunX) * (x - sunX) + (y - 21) * (y - 21) < 120 }, rgb(249, 217, 89))
        val peak = 65 + random.nextInt(35)
        paint(image, { x, y -> y in 24..78 && kotlin.math.abs(x - peak) * 3 < (y - 21) * 2 }, rgb(106, 104, 113))
        paint(image, { x, y -> y > 70 && kotlin.math.abs(x - (y - 70) * 2 - 25) < 10 }, rgb(42, 126, 181))
        repeat(7) {
            val left = 8 + random.nextInt(142)
            val top = 47 + random.nextInt(34)
            rectangle(image, left - 2, top + 6, left + 2, top + 28, rgb(85, 51, 29))
            paint(image, { x, y -> y in top..top + 20 && kotlin.math.abs(x - left) < (y - top) / 2 + 2 }, rgb(18, 66 + it * 5, 38))
        }
        val house = 99 + random.nextInt(17)
        rectangle(image, house, 65, house + 26, 91, rgb(199, 135, 68))
        paint(image, { x, y -> y in 54..66 && kotlin.math.abs(x - house - 13) < y - 51 }, rgb(132, 49, 34))
        rectangle(image, house + 10, 77, house + 17, 91, rgb(47, 39, 37))
        rectangle(image, house + 2, 70, house + 8, 76, rgb(232, 223, 158))
        return image
    }

    private val digits = listOf(
        "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
        "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111",
    )
    private fun document(seed: Long, digit: Int, page: Int): Raster {
        val random = Random(seed)
        val image = raster { _, _ -> rgb(242, 240, 232) }
        rectangle(image, 8, 6, 152, 12, rgb(26, 42, 62))
        repeat(8) { row ->
            val length = 86 + random.nextInt(50)
            rectangle(image, 13, 21 + row * 10, 13 + length - page * (row % 3) * 12, 23 + row * 10, rgb(55, 56, 57))
        }
        val glyph = digits[digit]
        for (y in 0..4) for (x in 0..2) if (glyph[y * 3 + x] == '1') {
            rectangle(image, 132 + x * 5, 69 + y * 5, 137 + x * 5, 74 + y * 5, rgb(13, 23, 33))
        }
        return image
    }

    private fun face(seed: Long, smiling: Boolean): Raster {
        val random = Random(seed)
        val image = raster { x, y -> rgb(30 + x / 4, 52 + y / 3, 83) }
        paint(image, { x, y -> (x - 81) * (x - 81) * 4 + (y - 58) * (y - 58) * 3 < 5000 }, rgb(213, 165, 126))
        val eye = 43 + random.nextInt(5)
        rectangle(image, 59, eye, 68, eye + 5, rgb(35, 31, 32))
        rectangle(image, 93, eye, 102, eye + 5, rgb(35, 31, 32))
        rectangle(image, 78, 53, 83, 68, rgb(170, 115, 92))
        paint(image, { x, y -> x in 63..100 && kotlin.math.abs(y - (if (smiling) 79 - (x - 82) * (x - 82) / 50 else 76)) <= 2 }, rgb(82, 32, 36))
        return image
    }

    private fun signal(seed: Long, after: Boolean): Raster {
        val image = scene(seed)
        rectangle(image, 117, 15, 145, 43, rgb(30, 30, 31))
        paint(image, { x, y -> (x - 131) * (x - 131) + (y - 29) * (y - 29) < 75 }, if (after) rgb(31, 224, 65) else rgb(231, 38, 42))
        return image
    }

    private fun resize(source: Raster, width: Int, height: Int): Raster = raster(width, height) { x, y ->
        source.argb[(y.toLong() * source.height / height).toInt() * source.width + (x.toLong() * source.width / width).toInt()]
    }

    private fun light(source: Raster, amount: Int): Raster = Raster(source.width, source.height, source.argb.map { color ->
        rgb(((color ushr 16) and 255) + amount, ((color ushr 8) and 255) + amount, (color and 255) + amount)
    }.toIntArray())

    private fun crop(source: Raster, margin: Int): Raster = raster(source.width - margin * 2, source.height - margin * 2) { x, y ->
        source.argb[(y + margin) * source.width + x + margin]
    }

    private fun colorize(source: Raster, red: Boolean): Raster = Raster(source.width, source.height, source.argb.map { color ->
        val level = (((color ushr 16) and 255) + ((color ushr 8) and 255) + (color and 255)) / 3
        if (red) rgb(level + 65, level / 3, level / 4) else rgb(level / 4, level / 3, level + 65)
    }.toIntArray())

    private fun alpha(source: Raster, hiddenRed: Boolean): Raster = raster(source.width, source.height) { x, y ->
        if (x < 12 && y < 12) (if (hiddenRed) 0x00FF0000 else 0x000000FF)
        else (160 shl 24) or (source.argb[y * source.width + x] and 0x00FFFFFF)
    }

    private fun rejectionFixtures(): List<RejectCase> {
        val source = scene(heldOutSeed + 7109)
        val random = Random(heldOutSeed + 7110)
        return listOf(
            RejectCase("reject-tiny", resize(source, 16, 16), "TINY"),
            RejectCase("reject-flat", raster { _, _ -> rgb(127, 127, 127) }, "LOW_INFORMATION"),
            RejectCase("reject-near-flat", raster { _, _ -> val value = 126 + random.nextInt(3); rgb(value, value, value) }, "LOW_INFORMATION"),
            RejectCase("reject-transparent", Raster(source.width, source.height, source.argb.map { it and 0x00FFFFFF }.toIntArray()), "TRANSPARENT"),
        )
    }

    // Independent EXIF oracle: place every source pixel at its specified oriented coordinate.
    private fun orient(source: Raster, orientation: Int): Raster {
        val swap = orientation in 5..8
        val width = if (swap) source.height else source.width
        val height = if (swap) source.width else source.height
        val output = IntArray(width * height)
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
                else -> error("Invalid fixture EXIF orientation")
            }
            output[dy * width + dx] = source.argb[y * source.width + x]
        }
        return Raster(width, height, output)
    }

    private fun storedWithExif(source: Raster, orientation: Int): Raster {
        val inverse = when (orientation) { 6 -> 8; 8 -> 6; else -> orientation }
        return orient(source, inverse).copy(orientation = orientation)
    }

    private fun jpeg(source: Raster, quality: Float): Raster {
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, source.width, source.height, source.argb, 0, source.width)
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(bytes).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam
                parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameters.compressionQuality = quality
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally { writer.dispose() }
        val decoded = ImageIO.read(ByteArrayInputStream(bytes.toByteArray())) ?: error("Fixture JPEG decode failed")
        return Raster(decoded.width, decoded.height, decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width))
    }

    private fun calibration(): List<Case> {
        val first = scene(calibrationSeed + 3101)
        val second = scene(calibrationSeed + 3102)
        return listOf(
            Case("cal-light", first, light(first, 14), Label.SAME_SCENE),
            Case("cal-resize", second, resize(second, 224, 156), Label.SAME_SCENE),
            Case("cal-jpeg", second, jpeg(second, 0.65f), Label.SAME_SCENE),
            Case("cal-subject", first, document(calibrationSeed + 3103, 2, 0), Label.DIFFERENT_CONTENT),
            Case("cal-color", colorize(first, true), colorize(first, false), Label.DIFFERENT_CONTENT),
            Case("cal-aspect", first, resize(first, 64, 192), Label.DIFFERENT_CONTENT),
        )
    }

    private fun heldOut(): List<Case> {
        val first = scene(heldOutSeed + 7101)
        val second = scene(heldOutSeed + 7102)
        val third = scene(heldOutSeed + 7103)
        val page = document(heldOutSeed + 7104, 3, 0)
        return listOf(
            Case("eval-light", first, light(first, 19), Label.SAME_SCENE),
            Case("eval-resize", second, resize(second, 240, 168), Label.SAME_SCENE),
            Case("eval-jpeg", third, jpeg(third, 0.53f), Label.SAME_SCENE),
            Case("eval-crop", third, crop(third, 5), Label.SAME_SCENE),
            Case("eval-subject", first, page, Label.DIFFERENT_CONTENT),
            Case("eval-document-digit", page, document(heldOutSeed + 7104, 8, 0), Label.DIFFERENT_CONTENT),
            Case("eval-document-page", page, document(heldOutSeed + 7104, 3, 1), Label.DIFFERENT_CONTENT),
            Case("eval-expression", face(heldOutSeed + 7105, false), face(heldOutSeed + 7105, true), Label.DIFFERENT_CONTENT),
            Case("eval-before-after", signal(heldOutSeed + 7106, false), signal(heldOutSeed + 7106, true), Label.DIFFERENT_CONTENT),
            Case("eval-color", colorize(scene(heldOutSeed + 7107), true), colorize(scene(heldOutSeed + 7107), false), Label.DIFFERENT_CONTENT),
            Case("eval-aspect", second, resize(second, 64, 192), Label.DIFFERENT_CONTENT),
            Case("eval-alpha", alpha(third, true), alpha(third, false), Label.SAME_SCENE),
            Case("eval-pattern", raster { x, y -> if ((x / 8 + y / 8) % 2 == 0) rgb(225, 214, 193) else rgb(30, 47, 68) },
                raster { x, y -> if ((x / 8 + y / 8 + 1) % 2 == 0) rgb(225, 214, 193) else rgb(30, 47, 68) }, Label.DIFFERENT_CONTENT),
        ) + (1..8).map { orientation ->
            Case("eval-exif-$orientation", first, storedWithExif(first, orientation), Label.SAME_SCENE)
        }
    }
    // END FROZEN FIXTURES

    // BEGIN FROZEN V2 HOLDOUT
    // New labels and pixels freeze before the illumination-normalized pipeline is observed.
    private val freshSeed = 0x7E57B11D21L
    private fun plaza(seed: Long): Raster {
        val random = Random(seed)
        val image = raster(176, 128) { x, y ->
            if (y < 80) rgb(85 + x / 5, 137 + y / 7, 188 + x / 8)
            else rgb(121 + y / 8, 108 + x / 10, 97 + y / 12)
        }
        repeat(5) { building ->
            val left = 3 + building * 35
            val top = 13 + random.nextInt(29)
            rectangle(image, left, top, left + 30, 89, rgb(90 + building * 23, 64 + building * 17, 108 + building * 9))
            for (floor in 0..3) for (column in 0..2) {
                rectangle(image, left + 3 + column * 9, top + 6 + floor * 11, left + 8 + column * 9, top + 12 + floor * 11, rgb(218, 206, 159))
            }
        }
        repeat(3) { umbrella ->
            val center = 26 + umbrella * 60 + random.nextInt(9)
            val top = 86 + random.nextInt(8)
            rectangle(image, center - 1, top + 1, center + 2, 119, rgb(52, 55, 61))
            paint(image, { x, y -> y in top - 9..top + 1 && (x - center) * (x - center) + (y - top) * (y - top) < 145 },
                if (umbrella % 2 == 0) rgb(190, 65, 53) else rgb(39, 131, 142))
        }
        rectangle(image, 18, 119, 150, 123, rgb(58, 63, 70))
        return image
    }

    private fun freshDocument(seed: Long, digit: Int, page: Int): Raster {
        val image = document(seed, digit, page)
        rectangle(image, 9, 15, 151, 18, rgb(65, 85, 112))
        rectangle(image, 75, 28, 78, 103, rgb(87, 89, 95))
        rectangle(image, 9, 49, 151, 52, rgb(87, 89, 95))
        return image
    }

    private fun eyelids(seed: Long, closed: Boolean): Raster {
        val image = face(seed, false)
        if (closed) {
            rectangle(image, 57, 41, 71, 52, rgb(213, 165, 126))
            rectangle(image, 91, 41, 105, 52, rgb(213, 165, 126))
            rectangle(image, 59, 47, 68, 49, rgb(35, 31, 32))
            rectangle(image, 93, 47, 102, 49, rgb(35, 31, 32))
        }
        return image
    }

    private fun meter(seed: Long, filled: Boolean): Raster {
        val image = plaza(seed)
        rectangle(image, 141, 78, 169, 115, rgb(45, 52, 63))
        rectangle(image, 146, if (filled) 84 else 102, 164, 109, rgb(51, 185, 156))
        return image
    }

    private fun freshHeldOut(): List<Case> {
        val first = plaza(freshSeed + 8201)
        val second = plaza(freshSeed + 8202)
        val third = plaza(freshSeed + 8203)
        val fourth = plaza(freshSeed + 8204)
        val page = freshDocument(freshSeed + 8205, 6, 0)
        return listOf(
            Case("fresh-light", first, light(first, 17), Label.SAME_SCENE),
            Case("fresh-brighter", second, light(second, 24), Label.SAME_SCENE),
            Case("fresh-darker", third, light(third, -13), Label.SAME_SCENE),
            Case("fresh-resize", second, resize(second, 242, 176), Label.SAME_SCENE),
            Case("fresh-jpeg", third, jpeg(third, 0.47f), Label.SAME_SCENE),
            Case("fresh-crop", first, crop(first, 7), Label.SAME_SCENE),
            Case("fresh-subject", first, page, Label.DIFFERENT_CONTENT),
            Case("fresh-document-digit", page, freshDocument(freshSeed + 8205, 9, 0), Label.DIFFERENT_CONTENT),
            Case("fresh-document-page", page, freshDocument(freshSeed + 8205, 6, 2), Label.DIFFERENT_CONTENT),
            Case("fresh-expression", eyelids(freshSeed + 8206, false), eyelids(freshSeed + 8206, true), Label.DIFFERENT_CONTENT),
            Case("fresh-before-after", meter(freshSeed + 8207, false), meter(freshSeed + 8207, true), Label.DIFFERENT_CONTENT),
            Case("fresh-color", colorize(plaza(freshSeed + 8208), true), colorize(plaza(freshSeed + 8208), false), Label.DIFFERENT_CONTENT),
            Case("fresh-aspect", third, resize(third, 64, 224), Label.DIFFERENT_CONTENT),
            Case("fresh-alpha", alpha(fourth, true), alpha(fourth, false), Label.SAME_SCENE),
            Case("fresh-pattern", raster { x, _ -> if (x / 11 % 2 == 0) rgb(41, 92, 142) else rgb(212, 174, 121) },
                raster { _, y -> if (y / 11 % 2 == 0) rgb(41, 92, 142) else rgb(212, 174, 121) }, Label.DIFFERENT_CONTENT),
        ) + (1..8).map { orientation ->
            Case("fresh-exif-$orientation", fourth, storedWithExif(fourth, orientation), Label.SAME_SCENE)
        }
    }
    // END FROZEN V2 HOLDOUT

    private fun feature(image: Raster): SimilarityFeature = when (val result = similarityFeature(image.width, image.height, image.argb, image.orientation)) {
        is SimilarityPixelResult.Accepted -> result.feature
        is SimilarityPixelResult.Rejected -> error("Expected informative fixture, rejected ${result.reason}")
    }

    private data class Observation(val case: Case, val candidate: Boolean, val first: String, val second: String, val distance: SimilarityDistance?)
    private fun evaluate(partition: String, cases: List<Case>): List<Observation> = cases.map { case ->
        val first = similarityFeature(case.first.width, case.first.height, case.first.argb, case.first.orientation)
        val second = similarityFeature(case.second.width, case.second.height, case.second.argb, case.second.orientation)
        val left = (first as? SimilarityPixelResult.Accepted)?.feature
        val right = (second as? SimilarityPixelResult.Accepted)?.feature
        val measured = if (left != null && right != null) left.distances(right) else null
        val candidate = left != null && right != null && similarityDistance(left, right) != null
        fun status(result: SimilarityPixelResult) = when (result) {
            is SimilarityPixelResult.Accepted -> result.feature.algorithmVersion
            is SimilarityPixelResult.Rejected -> "rejected:${result.reason}"
        }
        if (measured != null) {
            assertTrue(measured.pHashDistance in 0..64)
            assertTrue(listOf(measured.colorDistance, measured.spatialDistance, measured.aspectDistance).all { it.isFinite() && it >= 0 })
        }
        Observation(case, candidate, status(first), status(second), measured).also {
            println("S36_EVAL|$partition|${case.id}|${case.label}|candidate=$candidate|first=${it.first}|second=${it.second}|distance=$measured")
        }
    }.also { observations ->
        val falsePositives = observations.filter { it.case.label == Label.DIFFERENT_CONTENT && it.candidate }.map { it.case.id }
        val misses = observations.filter { it.case.label == Label.SAME_SCENE && !it.candidate }.map { it.case.id }
        println("S36_SUMMARY|$partition|cases=${observations.size}|candidateFalsePositives=$falsePositives|misses=$misses")
    }

    private fun item(number: Int, year: Int = 2024): MediaItem = MediaItem(
        "content://media/external_primary/images/media/$number", "$number.jpg", "image/jpeg", false,
        java.time.LocalDate.of(year, 1, 1).toEpochDay() * 86_400_000, 160, 112, 1024, 0, "album", "Album",
        providerAuthority = "media", volumeName = "external_primary", providerVersion = "v1",
        providerGeneration = 30, externalHandle = number.toString(), generationAdded = number.toLong(), generationModified = 20,
    )
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun source(item: MediaItem, feature: SimilarityFeature, encoding: String = item.id) =
        SimilarityAnalyzedSource(item, feature, digest(encoding), 1024)
    private fun coverage(count: Int) = SimilarityCoverage(selected = count, attempted = count, featured = count, admittedBytes = count * 1024L)
    private fun expectFailure(reason: SimilarityFailure, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (failure: SimilarityException) { assertEquals(reason, failure.reason) }
    }
    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Inconsistent analysis coverage must not publish") } catch (_: IllegalArgumentException) { }
    }

    @Test fun calibrationPartitionRemainsSeparateFromHeldOutObservations() {
        val cases = calibration()
        assertEquals(6, cases.size)
        assertEquals(3, cases.count { it.label == Label.SAME_SCENE })
        assertTrue(cases.none { it.id in heldOut().map(Case::id) })
        val observations = evaluate("independent-calibration-crosscheck", cases)
        for (observation in observations) assertEquals(observation.case.id, observation.case.label == Label.SAME_SCENE, observation.candidate)
    }

    @Test fun frozenHeldOutPixelsRetrieveClearPositivesAndReportHardCollisions() {
        val cases = heldOut()
        assertEquals(21, cases.size)
        assertEquals(13, cases.count { it.label == Label.SAME_SCENE })
        assertEquals(8, cases.count { it.label == Label.DIFFERENT_CONTENT })
        assertEquals(cases.size, cases.map(Case::id).toSet().size)
        // Once the lighting miss informed v2, this original set became exposed regression data.
        val observations = evaluate("exposed-v1-regression", cases).associateBy { it.case.id }
        for (id in listOf("eval-light", "eval-resize", "eval-jpeg") + (1..8).map { "eval-exif-$it" }) {
            assertTrue("Clear held-out positive $id must remain retrievable", observations.getValue(id).candidate)
        }
        for (id in listOf("eval-subject", "eval-color", "eval-aspect")) {
            assertFalse("Clearly different held-out content $id must not be a candidate", observations.getValue(id).candidate)
        }
        // Crop/alpha misses and document/state/pattern collisions remain visible observations.
        // They are never retagged as equivalent copies or used to tune frozen thresholds.
    }

    @Test fun newIndependentHoldoutRetrievesUsefulPositivesAfterV2CalibrationFreeze() {
        val cases = freshHeldOut()
        assertEquals(23, cases.size)
        assertEquals(15, cases.count { it.label == Label.SAME_SCENE })
        assertEquals(8, cases.count { it.label == Label.DIFFERENT_CONTENT })
        assertTrue(cases.none { fresh -> fresh.id in heldOut().map(Case::id) })
        val observations = evaluate("held-out-S36-v2", cases).associateBy { it.case.id }
        val clear = listOf("fresh-light", "fresh-brighter", "fresh-darker", "fresh-resize", "fresh-jpeg") + (1..8).map { "fresh-exif-$it" }
        for (id in clear) assertTrue("New held-out clear positive $id must be retrievable", observations.getValue(id).candidate)
        for (id in listOf("fresh-subject", "fresh-color", "fresh-aspect")) {
            assertFalse("New held-out clearly different $id must not be a candidate", observations.getValue(id).candidate)
        }
    }

    @Test fun exifOneThroughEightUsesAnIndependentPixelPlacementOracle() {
        val original = scene(heldOutSeed + 7101)
        val baseline = feature(original)
        for (orientation in 1..8) {
            val stored = storedWithExif(original, orientation)
            val restored = orient(stored, orientation)
            assertEquals(original.width, restored.width)
            assertEquals(original.height, restored.height)
            assertArrayEquals(original.argb, restored.argb)
            val actual = feature(stored)
            assertEquals("EXIF $orientation must normalize the same pixels", baseline.perceptualHash, actual.perceptualHash)
            assertEquals(baseline.aspectRatio, actual.aspectRatio, 0.0)
            assertEquals(SimilarityDistance(0, 0.0, 0.0, 0.0), similarityDistance(baseline, actual))
        }
    }

    @Test fun actualImageIoJpegReencodingChangesPixelsButKeepsSceneRetrievable() {
        val original = scene(heldOutSeed + 7103)
        val reencoded = jpeg(original, 0.53f)
        assertFalse("Exercise an actual lossy JPEG round trip", original.argb.contentEquals(reencoded.argb))
        assertNotNull(similarityDistance(feature(original), feature(reencoded)))
    }

    @Test fun tinyFlatNearFlatAndAlphaFixturesAreRejectedExplicitly() {
        val fixtures = rejectionFixtures()
        assertEquals(4, fixtures.size)
        for (case in fixtures) {
            val result = similarityFeature(case.image.width, case.image.height, case.image.argb, case.image.orientation)
            assertTrue("${case.id} must be omitted rather than matched", result is SimilarityPixelResult.Rejected)
            assertEquals(case.id, case.reason, (result as SimilarityPixelResult.Rejected).reason.name)
        }
    }

    @Test fun invalidPixelShapesAndOrientationsCannotBecomeDescriptors() {
        val pixels = scene(calibrationSeed + 3101).argb
        for (result in listOf(
            similarityFeature(160, 112, pixels.copyOf(pixels.size - 1)),
            similarityFeature(Int.MAX_VALUE, Int.MAX_VALUE, pixels),
            similarityFeature(160, 112, pixels, 0), similarityFeature(160, 112, pixels, 9),
        )) {
            assertTrue(result is SimilarityPixelResult.Rejected)
            assertEquals(SimilarityPixelSkip.INVALID, (result as SimilarityPixelResult.Rejected).reason)
        }
    }

    @Test fun appearanceRetrievalAcrossTwentyFourYearsRemainsReviewOnly() {
        val image = scene(heldOutSeed + 7101)
        val selected = listOf(item(1, 2000), item(2, 2024))
        val session = SimilaritySession()
        val ticket = session.begin(selected) {}
        val result = session.finish(ticket, listOf(source(selected[0], feature(image)), source(selected[1], feature(light(image, 19)))), coverage(2))
        assertEquals(1, result.pairs.size)
        assertTrue(result.reviewOnly)
        assertTrue(result.pairs.single().reviewOnly)
        assertEquals(selected, session.resolve(result, result.pairs.single()))
        assertEquals(1, result.coverage.pairChecks)
        assertEquals(0, result.coverage.exactPairsOmitted)
        assertEquals(2, result.coverage.featured)
    }

    @Test fun equalRepresentationDigestsFollowExactOmissionWithoutMutationAuthority() {
        val descriptor = feature(scene(calibrationSeed + 3101))
        val selected = listOf(item(3), item(4))
        val session = SimilaritySession()
        val ticket = session.begin(selected) {}
        val sources = selected.map { source(it, descriptor, "same-readable-representation") }
        val result = session.finish(ticket, sources, coverage(2))
        assertTrue(result.pairs.isEmpty())
        assertEquals(1, result.coverage.exactPairsOmitted)
        assertEquals(1, result.coverage.pairChecks)
        assertTrue(result.reviewOnly)
    }

    @Test fun directAppearancePairsNeverAddATransitiveThirdRelationship() {
        val base = scene(calibrationSeed + 3150)
        val features = (0..128 step 8).mapNotNull { level ->
            (similarityFeature(base.width, base.height, light(base, level).argb) as? SimilarityPixelResult.Accepted)?.feature
        }
        var chain: List<SimilarityFeature>? = null
        search@ for (a in features.indices) for (b in a + 1 until features.size) for (c in b + 1 until features.size) {
            if (similarityDistance(features[a], features[b]) != null && similarityDistance(features[b], features[c]) != null &&
                similarityDistance(features[a], features[c]) == null) {
                chain = listOf(features[a], features[b], features[c]); break@search
            }
        }
        assertNotNull("Frozen calibration light sequence must exhibit direct nontransitivity", chain)
        val selected = (11..13).map { item(it) }
        val session = SimilaritySession()
        val ticket = session.begin(selected) {}
        val result = session.finish(ticket, selected.mapIndexed { index, item -> source(item, chain!![index]) }, coverage(3))
        val actual = result.pairs.map { setOf(it.first.id, it.second.id) }.toSet()
        assertEquals(setOf(setOf(selected[0].id, selected[1].id), setOf(selected[1].id, selected[2].id)), actual)
        assertEquals(3, result.coverage.pairChecks)
        assertTrue(result.pairs.all { it.reviewOnly })
    }

    @Test fun allTwoHundredSourcesAreCheckedAndOnlyFiftyPairsReturnedWithOmittedCount() {
        val selected = (1001..1200).map { item(it, if (it % 2 == 0) 2000 else 2024) }
        val descriptor = feature(scene(calibrationSeed + 3102))
        val session = SimilaritySession()
        val ticket = session.begin(selected) {}
        val result = session.finish(ticket, selected.map { source(it, descriptor) }, coverage(200))
        assertEquals(200, result.coverage.selected)
        assertEquals(200, result.coverage.featured)
        assertEquals(19_900, result.coverage.pairChecks)
        assertEquals(50, result.pairs.size)
        assertEquals(19_850, result.coverage.resultPairsOmitted)
        assertEquals(0, result.coverage.exactPairsOmitted)
        try { (result.pairs as MutableList<SimilarityPair>).clear(); fail("Results must be immutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(50, result.pairs.size)
    }

    @Test fun invalidSelectionSizesRejectInsteadOfSilentlyTruncatingScope() {
        val session = SimilaritySession()
        for (selected in listOf(emptyList(), listOf(item(1)), (1..201).map { item(it) })) {
            expectFailure(SimilarityFailure.INVALID_SELECTION) { session.begin(selected) {} }
        }
    }

    @Test fun coverageCannotClaimSuccessfulFeaturesWithoutProviderAttempts() {
        val selected = listOf(item(31), item(32))
        val descriptor = feature(scene(calibrationSeed + 3101))
        val sources = selected.map { source(it, descriptor) }
        val noAttempt = SimilaritySession()
        val noAttemptTicket = noAttempt.begin(selected) {}
        expectInvalid { noAttempt.finish(noAttemptTicket, sources, coverage(2).copy(attempted = 0)) }
    }

    @Test fun admittedBytesMustIncludeEverySuccessfulRepresentation() {
        val selected = listOf(item(31), item(32))
        val descriptor = feature(scene(calibrationSeed + 3101))
        val sources = selected.map { source(it, descriptor) }
        val shortBytes = SimilaritySession()
        val shortBytesTicket = shortBytes.begin(selected) {}
        expectInvalid { shortBytes.finish(shortBytesTicket, sources, coverage(2).copy(admittedBytes = 2047)) }
    }

    @Test fun sameShapedReplacementAndForeignPairsCannotRetargetReviewAuthority() {
        val selected = listOf(item(41), item(42))
        val descriptor = feature(scene(calibrationSeed + 3101))
        val session = SimilaritySession()
        val firstTicket = session.begin(selected) {}
        val first = session.finish(firstTicket, selected.map { source(it, descriptor) }, coverage(2))
        val secondTicket = session.begin(selected) {}
        val second = session.finish(secondTicket, selected.map { source(it, descriptor) }, coverage(2))
        assertNull(session.resolve(first, first.pairs.single()))
        assertNull(session.resolve(second, first.pairs.single()))
        assertEquals(selected, session.resolve(second, second.pairs.single()))
        val foreign = SimilaritySession()
        assertNull(foreign.resolve(second, second.pairs.single()))
        assertFalse(session.isCurrent(firstTicket))
        session.invalidate(firstTicket)
        assertTrue(session.isCurrent(secondTicket))
        session.invalidate()
        assertNull(session.resolve(second, second.pairs.single()))
        assertTrue(second.reviewOnly)
    }

    @Test fun liveAuthorityWithdrawalFencesPairsAndCannotReviveOnAccessReturn() {
        val selected = listOf(item(51), item(52))
        val descriptor = feature(scene(calibrationSeed + 3101))
        var authorized = true
        val session = SimilaritySession()
        val ticket = session.begin(selected) { check(authorized) }
        val result = session.finish(ticket, selected.map { source(it, descriptor) }, coverage(2))
        assertNotNull(session.resolve(result, result.pairs.single()))
        authorized = false
        assertNull(session.resolve(result, result.pairs.single()))
        authorized = true
        assertNull(session.resolve(result, result.pairs.single()))
        assertFalse(session.isCurrent(ticket))
    }
}
