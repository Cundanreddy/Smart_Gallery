package com.example.smartgallery

data class ImageGroup(val assets: List<MediaAsset>, val isDuplicate: Boolean)

object GroupingUtils {
    fun groupByShaAndPhash(list: List<MediaAsset>): List<ImageGroup> {
        // exact duplicates first by sha
        val bySha = list.groupBy { it.sha256 ?: "" }
            .filterKeys { it.isNotEmpty() }
            .map { (_, v) -> ImageGroup(v, v.size > 1) }
            .toMutableList()

        // remaining items without sha (or unique sha) -> near-duplicates by dhash
        val remaining = list.filter { it.sha256 == null || (bySha.none { g -> g.assets.any { a -> a.sha256 == it.sha256 } }) }
        val used = mutableSetOf<MediaAsset>()

        for (i in remaining.indices) {
            val root = remaining[i]
            if (used.contains(root)) continue
            val group = mutableListOf(root)
            for (j in i+1 until remaining.size) {
                val other = remaining[j]
                if (used.contains(other)) continue
                val d1 = root.dhash
                val d2 = other.dhash
                if (d1 != null && d2 != null) {
                    val ham = ImageHashing.hammingDistanceHex(d1, d2)
                    if (ham <= 6) { // threshold - tune later
                        group.add(other)
                        used.add(other)
                    }
                }
            }
            used.add(root)
            if (group.size > 1) bySha.add(ImageGroup(group, true))
            else bySha.add(ImageGroup(group, false))
        }
        return bySha
    }
}
