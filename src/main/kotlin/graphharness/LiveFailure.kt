package graphharness

class LiveFailure(val code: String, val httpStatus: Int, message: String, val details: JObject = emptyJsonObject()) : RuntimeException(message)
