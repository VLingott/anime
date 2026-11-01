package dev.anime.util

fun isNewerVersion(current: String, latest: String): Boolean {
    fun parts(value: String) = value.removePrefix("v")
        .split('.', '-', '+', '_')
        .mapNotNull { it.toIntOrNull() }
    val a = parts(current)
    val b = parts(latest)
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return y > x
    }
    return false
}
