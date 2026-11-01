package dev.anime.source

import dev.anime.model.SearchHit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/** Shared per-request state handed to every adapter. */
class SourceContext(
    val http: HttpClient,
    val timeoutMs: Long = 6_000,
)

fun buildHttpClient(userAgent: String): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 8_000
        connectTimeoutMillis = 3_000
        socketTimeoutMillis = 8_000
    }
    expectSuccess = false
    followRedirects = true
    defaultRequest {
        header(HttpHeaders.UserAgent, userAgent)
        header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
    }
}
