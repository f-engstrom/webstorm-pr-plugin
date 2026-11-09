package se.personal.adoprreview.api

import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One HTTP request, as [AdoClient] and the Jira client build it. */
class HttpCall(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    /** Null for no body. Note java.net.http sends no Content-Length for a bodiless POST; ADO rejects that with 411. */
    val body: ByteArray? = null,
    val timeout: Duration = Duration.ofSeconds(60),
) {
    val bodyText: String? get() = body?.toString(Charsets.UTF_8)
}

class HttpReply(val status: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/**
 * Sends a request and returns whatever status came back; never follows redirects (ADO answers a bad token with a
 * redirect to its sign-in page). Throws [java.io.IOException] when the server can't be reached, and
 * [IllegalArgumentException] for a URL it can't send to. Production: [JavaHttpTransport]; tests: canned replies.
 */
fun interface HttpTransport {
    suspend fun send(call: HttpCall): HttpReply
}

class JavaHttpTransport : HttpTransport {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun send(call: HttpCall): HttpReply {
        val builder = HttpRequest.newBuilder(URI(call.url)).timeout(call.timeout)
        call.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = call.body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody()
        val response = http.sendAsync(builder.method(call.method, body).build(), HttpResponse.BodyHandlers.ofByteArray()).await()
        return HttpReply(response.statusCode(), response.body())
    }
}
