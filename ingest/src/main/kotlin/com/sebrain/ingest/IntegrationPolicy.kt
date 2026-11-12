package com.sebrain.ingest

/**
 * Explicit package policy for integrations that can observe data without a Share action.
 * An empty policy denies every package; wildcards are intentionally unsupported.
 */
class IntegrationPolicy private constructor(
    private val allowedPackages: Set<String>,
) {
    fun allows(packageName: String): Boolean =
        packageName in allowedPackages

    fun packages(): Set<String> = allowedPackages

    companion object {
        fun fromPackages(packages: Iterable<String>): IntegrationPolicy {
            val normalized = packages
                .map(String::trim)
                .filter(String::isNotEmpty)
                .onEach { require(PACKAGE_NAME.matches(it)) { "Invalid Android package name" } }
                .toSet()
            return IntegrationPolicy(normalized)
        }

        fun denyAll(): IntegrationPolicy = IntegrationPolicy(emptySet())

        private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    }
}
