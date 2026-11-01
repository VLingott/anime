package dev.anime.util

fun cycleFilter(
    include: Set<String>,
    exclude: Set<String>,
    value: String,
): Pair<Set<String>, Set<String>> = when {
    value in include -> include - value to exclude + value
    value in exclude -> include to exclude - value
    else -> include + value to exclude
}
