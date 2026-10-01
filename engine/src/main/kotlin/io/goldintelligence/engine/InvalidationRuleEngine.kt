package io.goldintelligence.engine

/**
 * Every thesis must carry an explicit invalidation condition (spec item
 * "Signal Invalidation"), preventing the model from anchoring indefinitely
 * on a stale analysis.
 */
class RuleBasedInvalidationEngine : InvalidationEngine {
    override fun invalidate(snapshot: InputSnapshot): String? {
        val dominantId = snapshot.dominantFactor ?: return null
        val meta = FactorCatalog.byId[dominantId] ?: return null
        return "Invalidated if ${meta.nameEn} reverses direction OR the Regime transitions out of " +
            "${snapshot.regime.name} OR Gold breaks the prevailing structural level."
    }
}
