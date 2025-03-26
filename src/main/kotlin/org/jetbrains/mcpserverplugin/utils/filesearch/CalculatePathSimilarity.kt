package org.jetbrains.mcpserverplugin.utils.filesearch

import kotlin.math.max

/**
 * Calculates a similarity score between the search path and a candidate path.
 * Higher scores indicate better matches.
 * 
 * @param searchPath The normalized search path (lowercased, forward slashes)
 * @param candidatePath The normalized candidate path (lowercased, forward slashes)
 * @return A score representing how well the paths match (higher is better)
 */
internal fun FileSearch.calculatePathSimilarity(searchPath: String, candidatePath: String): Int {
    // The strategy for scoring:
    // 1. Exact match gets highest score
    // 2. Path endings should match (file or directory name)
    // 3. More matching path components = higher score
    
    // We'll compare the full paths, focusing on matching components
    
    // Start with a base score
    var score = 0
    
    // Exact match is best
    if (candidatePath.endsWith(searchPath)) {
        score += 1000
    }
    
    // If not exact, check how many path components match from the end
    val searchComponents = searchPath.split("/")
    val candidateComponents = candidatePath.split("/")
    
    // Start from the end and count matching components
    var matchingComponents = 0
    for (i in 0 until max(searchComponents.size, candidateComponents.size)) {
        val searchIndex = searchComponents.size - 1 - i
        val candidateIndex = candidateComponents.size - 1 - i
        
        if (searchIndex < 0 || candidateIndex < 0) {
            break
        }
        
        if (searchComponents[searchIndex] == candidateComponents[candidateIndex]) {
            matchingComponents++
        } else {
            break
        }
    }
    
    // Add score for matching components
    score += matchingComponents * 100
    
    return score
}