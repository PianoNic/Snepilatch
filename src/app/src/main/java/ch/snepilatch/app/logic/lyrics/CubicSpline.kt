package ch.snepilatch.app.logic.lyrics

/**
 * A natural cubic spline through a handful of (x, y) control points, sampled with [at]. The lyrics
 * animation describes every curve (scale, lift, glow over a word's progress) as such a spline and
 * feeds the sampled value to a [Spring] as its goal. Inputs outside the control range clamp to the
 * ends.
 */
class CubicSpline(private val xs: FloatArray, private val ys: FloatArray) {

    /** Second derivatives at the knots, with the natural boundary of zero at both ends. */
    private val m: FloatArray = solve()

    constructor(vararg points: Pair<Float, Float>) :
        this(FloatArray(points.size) { points[it].first }, FloatArray(points.size) { points[it].second })

    fun at(x: Float): Float {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var i = 0
        while (i < n - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val a = (xs[i + 1] - x) / h
        val b = (x - xs[i]) / h
        return a * ys[i] + b * ys[i + 1] + ((a * a * a - a) * m[i] + (b * b * b - b) * m[i + 1]) * h * h / 6f
    }

    private fun solve(): FloatArray {
        val n = xs.size
        require(n >= 2 && ys.size == n) { "a spline needs at least two points" }
        val result = FloatArray(n)
        if (n == 2) return result
        val h = FloatArray(n - 1) { xs[it + 1] - xs[it] }
        // Tridiagonal system for the interior knots, solved by the Thomas algorithm.
        val diag = FloatArray(n)
        val upper = FloatArray(n)
        val rhs = FloatArray(n)
        for (i in 1 until n - 1) {
            diag[i] = 2f * (h[i - 1] + h[i])
            upper[i] = h[i]
            rhs[i] = 6f * ((ys[i + 1] - ys[i]) / h[i] - (ys[i] - ys[i - 1]) / h[i - 1])
        }
        for (i in 2 until n - 1) {
            val factor = h[i - 1] / diag[i - 1]
            diag[i] -= factor * upper[i - 1]
            rhs[i] -= factor * rhs[i - 1]
        }
        result[n - 2] = rhs[n - 2] / diag[n - 2]
        for (i in n - 3 downTo 1) result[i] = (rhs[i] - upper[i] * result[i + 1]) / diag[i]
        return result
    }
}
