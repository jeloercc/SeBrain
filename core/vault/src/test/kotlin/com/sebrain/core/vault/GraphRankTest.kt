package com.sebrain.core.vault

import com.sebrain.core.vault.GraphRank.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRankTest {
    @Test
    fun onAChainTheScoreFallsWithDistanceFromTheSeed() {
        val chain = listOf(Edge(1, 2, 1.0), Edge(2, 3, 1.0), Edge(3, 4, 1.0))
        val rank = GraphRank.personalized(chain, mapOf(1L to 1.0)).toMap()
        // The seed's neighbour gathers score from both sides and can pass the seed; beyond it the
        // score falls with each step.
        assertTrue(rank.getValue(1) > rank.getValue(3))
        assertTrue(rank.getValue(2) > rank.getValue(3))
        assertTrue(rank.getValue(3) > rank.getValue(4))
        assertEquals(1.0, rank.values.sum(), 1e-9)
    }

    @Test
    fun aHubLinkedToSeveralSeedsOutranksANoteLinkedToOne() {
        // 10 is linked to both seeds; 20 only to one of them.
        val edges = listOf(Edge(1, 10, 1.0), Edge(2, 10, 1.0), Edge(1, 20, 1.0))
        val rank = GraphRank.personalized(edges, mapOf(1L to 1.0, 2L to 1.0)).toMap()
        assertTrue(rank.getValue(10) > rank.getValue(20))
    }

    @Test
    fun aLinkWeighsMoreThanAWeakTag() {
        val edges = listOf(Edge(1, 2, 1.0), Edge(1, 3, 0.1))
        val rank = GraphRank.personalized(edges, mapOf(1L to 1.0)).toMap()
        assertTrue(rank.getValue(2) > rank.getValue(3))
    }

    @Test
    fun aSeparateClusterGetsNothingAndNoSeedsGivesNothing() {
        val edges = listOf(Edge(1, 2, 1.0), Edge(5, 6, 1.0))
        val rank = GraphRank.personalized(edges, mapOf(1L to 1.0)).toMap()
        assertEquals(null, rank[5])
        assertTrue(GraphRank.personalized(edges, emptyMap()).isEmpty())
        // A seed without edges keeps all the score.
        assertEquals(1.0, GraphRank.personalized(emptyList(), mapOf(9L to 2.0)).single().second, 1e-9)
    }
}
