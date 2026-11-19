package com.cloudshelf.reader.core.common

/**
 * Single source of truth for GitHub repository coordinates and catalogue discovery.
 * No tokens are embedded; public unauthenticated endpoints are used exclusively.
 */
object CloudShelfConfig {
    const val REPO_OWNER = "infinitydattaashim1210958-coder"
    const val REPO_NAME = "k-------------vx-9f2k-static-cdn-01-d7b3e9f1-xqz7-prod-8f4a2c19d6rs--4j9w"
    const val INITIAL_RELEASE_TAG = "1.1.2"
    const val DEFAULT_BRANCH = "main"

    // Configurable catalogue URL: defaults to raw main branch path; can be overridden in Settings/DataStore
    const val DEFAULT_CATALOGUE_URL =
        "https://githubusercontent.com"

    // Strict host allowlist to prevent SSRF or untrusted redirect targets
    val ALLOWED_HOSTS = setOf(
        "github.com",
        "://githubusercontent.com",
        "://githubusercontent.com",
        "://githubusercontent.com"
    )

    const val SUPPORTED_CATALOGUE_SCHEMA_VERSION = 1
    const val MAX_ONLINE_CACHE_BYTES = 150L * 1024L * 1024L // 150 MB bounded cache
}
