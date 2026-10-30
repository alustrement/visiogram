package app.visiogram.ui

import androidx.compose.ui.unit.IntRect
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_ROWS = 16

private class Block(val x: Int, val y: Int, val width: Int, val height: Int)

private class Packing(val pitch: Float, val blocks: List<Block>, val score: Float)

/**
 * Places tiles of the given aspect ratios (width / height) in a [width] × [height] area, each shown whole.
 *
 * The area is cut into square cells, and each tile takes a block of cells close to its own shape: 1×1 for a
 * square, 2×1 for a landscape, 1×2 for a portrait, and finer shapes as the cells get smaller. All tiles get
 * blocks of about the same area, each packed at the first free spot in reading order. Every cell size is tried
 * with the largest tiles that still fit, packing the tiles in their own order, tallest first or widest first;
 * the layout showing the most video wins, weighed by how little room it leaves empty between its blocks.
 *
 * Returns one rect per tile: the tile's own shape centred in its block, the whole packing centred in the area.
 */
fun layoutTiles(aspects: List<Float>, width: Int, height: Int, gap: Int): List<IntRect> {
    if (aspects.isEmpty() || width <= 0 || height <= 0) return emptyList()
    val indices = aspects.indices.toList()
    val orders = listOf(indices, indices.sortedBy { aspects[it] }, indices.sortedByDescending { aspects[it] })
    var best: Packing? = null
    for (order in orders) for (rows in 1..MAX_ROWS) {
        val pitch = (height + gap).toFloat() / rows
        val columns = ((width + gap) / pitch).toInt()
        if (columns < 1) continue
        // Tile sizes in half cells; the first one that fits is the best for this cell size.
        for (halfCells in 2 * rows downTo 1) {
            val size = halfCells / 2f
            val spans = aspects.map { a ->
                val w = (size * sqrt(a)).roundToInt().coerceIn(1, columns)
                val h = (size / sqrt(a)).roundToInt().coerceIn(1, rows)
                w to h
            }
            val blocks = pack(spans, order, columns, rows) ?: continue
            val video = blocks.indices.sumOf { i ->
                val (w, h) = fit(aspects[i], blocks[i].width * pitch - gap, blocks[i].height * pitch - gap)
                (w * h).toDouble()
            }
            val bounds = blocks.maxOf { it.x + it.width } * blocks.maxOf { it.y + it.height }
            val filled = blocks.sumOf { it.width * it.height }.toDouble() / bounds
            // Weighed enough that a tidy layout beats one with holes that shows only a bit more video.
            val score = (video * filled.pow(1.5)).toFloat()
            if (best == null || score > best.score) best = Packing(pitch, blocks, score)
            break
        }
    }
    val packing = best ?: return emptyList()
    val pitch = packing.pitch
    val usedWidth = packing.blocks.maxOf { it.x + it.width } * pitch - gap
    val usedHeight = packing.blocks.maxOf { it.y + it.height } * pitch - gap
    val left = (width - usedWidth) / 2
    val top = (height - usedHeight) / 2
    return packing.blocks.mapIndexed { i, block ->
        val blockWidth = block.width * pitch - gap
        val blockHeight = block.height * pitch - gap
        val (w, h) = fit(aspects[i], blockWidth, blockHeight)
        val x = left + block.x * pitch + (blockWidth - w) / 2
        val y = top + block.y * pitch + (blockHeight - h) / 2
        IntRect(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt())
    }
}

/** Largest size of the given aspect ratio inside [width] × [height]. */
private fun fit(aspect: Float, width: Float, height: Float): Pair<Float, Float> {
    val w = min(width, height * aspect)
    return w to w / aspect
}

/** Packs the blocks in [order], each at the first free spot in reading order; null if one finds no room. */
private fun pack(spans: List<Pair<Int, Int>>, order: List<Int>, columns: Int, rows: Int): List<Block>? {
    val taken = BooleanArray(columns * rows)
    fun free(x: Int, y: Int, w: Int, h: Int) = (y until y + h).all { yy -> (x until x + w).none { xx -> taken[yy * columns + xx] } }
    val blocks = arrayOfNulls<Block>(spans.size)
    for (i in order) {
        val (w, h) = spans[i]
        val (x, y) = (0..rows - h).asSequence()
            .flatMap { y -> (0..columns - w).asSequence().map { x -> x to y } }
            .firstOrNull { (x, y) -> free(x, y, w, h) }
            ?: return null
        for (yy in y until y + h) for (xx in x until x + w) taken[yy * columns + xx] = true
        blocks[i] = Block(x, y, w, h)
    }
    return blocks.requireNoNulls().toList()
}
