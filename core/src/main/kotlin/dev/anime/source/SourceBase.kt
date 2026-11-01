package dev.anime.source

import dev.anime.model.SearchHit
import dev.anime.model.StreamHit

interface SourceBase {
    val id: String
    val name: String
    val baseUrl: String

    /** "metadata" | "stream" — shown in the UI, nothing else depends on it. */
    val kind: String

    suspend fun probe(ctx: SourceContext): Boolean
}

interface MetadataSource : SourceBase {
    override suspend fun probe(ctx: SourceContext): Boolean = search("frieren", ctx).isNotEmpty()
    suspend fun search(query: String, ctx: SourceContext): List<SearchHit>
}

interface StreamSource : SourceBase {
    /** true when every returned `title` is curated/authoritative (metadata-style adapters). */
    val strictTitles: Boolean get() = false

    /** false for index-registered sites that only probe/rank — queries skip their search. */
    val searchable: Boolean get() = true

    override suspend fun probe(ctx: SourceContext): Boolean = search("frieren", ctx).isNotEmpty()
    suspend fun search(query: String, ctx: SourceContext): List<StreamHit>
}

typealias SourceAdapter = MetadataSource
