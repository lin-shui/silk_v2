package com.silk.backend.git

import java.net.URI

data class GitHubRepositoryRef(
    val owner: String,
    val repo: String,
) {
    val canonicalUrl: String get() = "https://github.com/$owner/$repo"
    val fullName: String get() = "$owner/$repo"

    companion object {
        fun parse(value: String): GitHubRepositoryRef? = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", ignoreCase = true))
            require(uri.host.equals("github.com", ignoreCase = true))
            require(uri.port == -1 && uri.userInfo == null)
            require(uri.rawQuery == null && uri.rawFragment == null)
            val path = uri.rawPath.removePrefix("/").removeSuffix("/")
            require(path.isNotBlank() && !path.contains("//"))
            val parts = path.split('/')
            require(parts.size == 2 && parts.all { it.isNotBlank() })
            val owner = decodeSegment(parts[0])
            val repo = decodeSegment(parts[1]).removeSuffix(".git")
            require(owner.isNotBlank() && repo.isNotBlank())
            require(owner.isSafeSegment() && repo.isSafeSegment())
            GitHubRepositoryRef(owner, repo)
        }.getOrNull()

        fun normalize(value: String): String =
            parse(value)?.canonicalUrl ?: throw IllegalArgumentException("Invalid GitHub repository URL")

        private fun decodeSegment(segment: String): String =
            java.net.URLDecoder.decode(segment, Charsets.UTF_8)

        private fun String.isSafeSegment(): Boolean =
            length <= 100 && all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
