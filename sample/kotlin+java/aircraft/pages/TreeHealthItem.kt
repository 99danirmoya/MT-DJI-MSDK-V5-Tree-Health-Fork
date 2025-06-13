package dji.sampleV5.aircraft.pages // Ensure this matches your package structure

/**
 * Data class to represent a single tree's health information.
 * @param treeId The unique identifier for the tree, starting from 0.
 * @param healthStatus The health status of the tree ("Healthy" or "Sick").
 */
data class TreeHealthItem(
    val treeId: Int,
    val healthStatus: String
)
