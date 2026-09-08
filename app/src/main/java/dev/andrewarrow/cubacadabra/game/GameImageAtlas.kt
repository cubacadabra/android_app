package dev.andrewarrow.cubacadabra.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

object GameImageAtlasBuilder {
    private const val MAX_ATLAS_DIMENSION = 2048
    private const val MAX_UPLOAD_DIMENSION = 1020
    private const val PADDING = 2

    fun make(assets: Map<String, LoadedGameImageAsset>): GameImageAtlas? {
        if (assets.isEmpty()) return null
        val images = assets.toSortedMap().map { (id, asset) -> decode(asset.data, id) }

        data class Placement(val image: DecodedGameImage, val x: Int, val y: Int)

        val placements = mutableListOf<Placement>()
        var x = PADDING
        var y = PADDING
        var rowHeight = 0
        for (image in images) {
            if (image.width + PADDING * 2 > MAX_ATLAS_DIMENSION ||
                image.height + PADDING * 2 > MAX_ATLAS_DIMENSION
            ) {
                throw GamePackageException("The game image asset \"${image.id}\" is invalid.")
            }
            if (x + image.width + PADDING > MAX_ATLAS_DIMENSION) {
                x = PADDING
                y += rowHeight + PADDING
                rowHeight = 0
            }
            if (y + image.height + PADDING > MAX_ATLAS_DIMENSION) {
                throw GamePackageException("The game images do not fit in the world texture atlas.")
            }
            placements += Placement(image, x, y)
            x += image.width + PADDING
            rowHeight = max(rowHeight, image.height)
        }

        val usedHeight = y + rowHeight + PADDING
        var height = 1
        while (height < usedHeight) height *= 2
        if (height > MAX_ATLAS_DIMENSION) {
            throw GamePackageException("The game images do not fit in the world texture atlas.")
        }

        val width = MAX_ATLAS_DIMENSION
        val atlasPixels = ByteArray(width * height * 4)
        val regions = JSONObject()
        placements.forEach { placement ->
            val image = placement.image
            for (row in 0 until image.height) {
                val sourceStart = row * image.width * 4
                val destinationStart = ((placement.y + row) * width + placement.x) * 4
                image.pixels.copyInto(
                    destination = atlasPixels,
                    destinationOffset = destinationStart,
                    startIndex = sourceStart,
                    endIndex = sourceStart + image.width * 4,
                )
            }
            regions.put(
                image.id,
                JSONArray(listOf(
                    (placement.x + 0.5) / width,
                    (placement.y + 0.5) / height,
                    max(1, image.width - 1).toDouble() / width,
                    max(1, image.height - 1).toDouble() / height,
                )),
            )
        }
        return GameImageAtlas(width, height, atlasPixels, regions.toString())
    }

    private fun decode(data: ByteArray, id: String): DecodedGameImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) invalidImage(id)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: invalidImage(id)
        val bitmap = if (max(decoded.width, decoded.height) > MAX_UPLOAD_DIMENSION) {
            val scale = MAX_UPLOAD_DIMENSION.toFloat() / max(decoded.width, decoded.height)
            val width = max(1, (decoded.width * scale).toInt())
            val height = max(1, (decoded.height * scale).toInt())
            Bitmap.createScaledBitmap(decoded, width, height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val pixels = ByteArray(argb.size * 4)
        argb.forEachIndexed { index, color ->
            val offset = index * 4
            pixels[offset] = (color shr 16).toByte()
            pixels[offset + 1] = (color shr 8).toByte()
            pixels[offset + 2] = color.toByte()
            pixels[offset + 3] = (color ushr 24).toByte()
        }
        val result = DecodedGameImage(id, bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        return result
    }

    private fun sampleSize(width: Int, height: Int): Int {
        val longestSide = max(width, height)
        var sample = 1
        while (longestSide / sample > MAX_UPLOAD_DIMENSION) sample *= 2
        return sample
    }

    private fun invalidImage(id: String): Nothing =
        throw GamePackageException("The game image asset \"$id\" is invalid.")

    private data class DecodedGameImage(
        val id: String,
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
    )
}
