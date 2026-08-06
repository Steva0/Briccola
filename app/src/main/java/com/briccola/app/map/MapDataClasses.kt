package com.briccola.app.map

/**
 * Rappresenta un nodo nel grafo dei canali (giunzione).
 */
data class CanalNode(
    val id: Long,
    val lat: Double,
    val lon: Double
)

/**
 * Rappresenta un arco (tratto di canale) tra due nodi.
 */
data class CanalEdge(
    val id: Long,
    val fromNodeId: Long,
    val toNodeId: Long,
    val name: String?,
    val maxSpeed: Int?, // km/h
    val minDepth: Float, // metri (precalcolato dal TIF)
    val length: Float, // metri
    val geometry: List<Pair<Double, Double>> // Lista di coordinate WGS84
)

/**
 * Rappresenta una briccola per il rendering o riferimento.
 */
data class Briccola(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val type: BriccolaType
)

enum class BriccolaType {
    DOLPHIN, PILE, MOORING
}