package io.freehand.selection.geometry

fun main() {
    val reversed = checkNotNull(SelectionRegion.rectangle(Point(90.8f, 70.2f), Point(10.3f, 20.9f)))
    check(reversed.pixelBounds(100, 100) == PixelBounds(10, 20, 91, 71))
    check(reversed.contains(Point(20f, 30f)))
    check(!reversed.contains(Point(5f, 30f)))
    check(SelectionRegion.rectangle(Point(1f, 1f), Point(1f, 40f)) == null)
    check(SelectionRegion.rectangle(Point(Float.NaN, 0f), Point(2f, 2f)) == null)

    // An L-shaped selection must exclude the neighbouring question in its bounding box.
    val concave = checkNotNull(SelectionRegion.freehand(listOf(Point(0f, 0f), Point(80f, 0f),
        Point(80f, 20f), Point(20f, 20f), Point(20f, 80f), Point(0f, 80f))))
    check(concave.contains(Point(10f, 60f)))
    check(concave.contains(Point(60f, 10f)))
    check(!concave.contains(Point(60f, 60f)))
    check(concave.copy(points = concave.points.reversed()).contains(Point(10f, 60f)))
    check(SelectionRegion.freehand(listOf(Point(0f, 0f), Point(20f, 20f), Point(40f, 40f))) == null)
    check(SelectionRegion.freehand(listOf(Point(0f, 0f), Point(40f, 40f),
        Point(0f, 40f), Point(40f, 0f))) != null)

    val edge = checkNotNull(SelectionRegion.rectangle(Point(-10f, -10f), Point(105f, 90f)))
    check(edge.pixelBounds(100, 80) == PixelBounds(0, 0, 100, 80))

    // Portrait screenshot in a landscape/multi-window surface, with letterbox margins.
    val mapping = ImageMapping(1000, 2000, 2000, 1000)
    check(mapping.scale == .5f && mapping.left == 750f && mapping.top == 0f)
    check(mapping.toImage(1000f, 500f) == Point(500f, 1000f))
    check(mapping.toImage(100f, 500f, clamp = false) == null)
    check(mapping.toImage(0f, 2000f) == Point(0f, 2000f))
    check(ImageMapping(1200, 800, 1200, 800).toImage(1200f, 800f) == Point(1200f, 800f))
    check(ImageMapping(1000, 1000, 0, 0).toImage(0f, 0f) == null)
    println("Geometry checks passed: reverse drags, real lasso exclusion, degenerate paths, clipping and screen mapping.")
}
