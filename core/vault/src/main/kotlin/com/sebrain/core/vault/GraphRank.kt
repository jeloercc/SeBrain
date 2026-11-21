package com.sebrain.core.vault

/**
 * Personalized PageRank over the note graph: how close each note is to a set of seed notes,
 * following links and shared tags. It is how graph-based retrieval (HippoRAG, the Obsidian LLM
 * wiki plugins) finds notes the search did not hit but that sit next to what it did. It is
 * plain arithmetic, with no model call.
 */
object GraphRank {
    /** An undirected edge between two notes, with a weight: 1 for a link, less for a shared tag. */
    data class Edge(val a: Long, val b: Long, val weight: Double)

    /**
     * The score of every node reachable from [seeds], highest first. [seeds] maps a note to how
     * strongly it was found; the walk restarts at the seeds with probability 1 - [damping].
     */
    fun personalized(edges: List<Edge>, seeds: Map<Long, Double>, damping: Double = 0.85, iterations: Int = 20): List<Pair<Long, Double>> {
        val total = seeds.values.sum()
        if (seeds.isEmpty() || total <= 0.0) return emptyList()
        val restart = seeds.mapValues { it.value / total }
        val neighbours = HashMap<Long, MutableList<Pair<Long, Double>>>()
        for (edge in edges) {
            if (edge.a == edge.b || edge.weight <= 0.0) continue
            neighbours.getOrPut(edge.a) { ArrayList() } += edge.b to edge.weight
            neighbours.getOrPut(edge.b) { ArrayList() } += edge.a to edge.weight
        }
        val outWeight = neighbours.mapValues { (_, list) -> list.sumOf { it.second } }
        var rank: Map<Long, Double> = restart
        repeat(iterations) {
            val next = HashMap<Long, Double>()
            restart.forEach { (node, share) -> next.merge(node, (1 - damping) * share, Double::plus) }
            for ((node, score) in rank) {
                val list = neighbours[node]
                if (list.isNullOrEmpty()) {
                    // A note with no edge gives its score back to the seeds, so nothing leaks away.
                    restart.forEach { (seed, share) -> next.merge(seed, damping * score * share, Double::plus) }
                } else {
                    val out = outWeight.getValue(node)
                    list.forEach { (other, weight) -> next.merge(other, damping * score * weight / out, Double::plus) }
                }
            }
            rank = next
        }
        return rank.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
