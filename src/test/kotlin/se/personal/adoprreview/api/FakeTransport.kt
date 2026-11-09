package se.personal.adoprreview.api

/** Replies in order (an [Exception] entry is thrown instead), recording every call. */
class FakeTransport(vararg replies: Any) : HttpTransport {
    private val queue = ArrayDeque(replies.toList())
    val calls = mutableListOf<HttpCall>()

    override suspend fun send(call: HttpCall): HttpReply {
        calls += call
        return when (val next = queue.removeFirstOrNull() ?: error("No reply left for ${call.method} ${call.url}")) {
            is HttpReply -> next
            is Exception -> throw next
            else -> error("Bad canned reply: $next")
        }
    }

    companion object {
        fun ok(json: String = "{}") = HttpReply(200, json.toByteArray())
        fun status(code: Int, body: String = "") = HttpReply(code, body.toByteArray())
    }
}
