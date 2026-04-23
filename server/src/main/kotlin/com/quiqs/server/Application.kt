package com.quiqs.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Placeholder Ktor server. Currently exposes only a `/health` probe — the app is
 * fully client-side while on-device LLM inference is validated. Server work is queued
 * for a later phase, after the mobile feature set stabilises.
 */
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/** Registers plugins and routes on the Ktor [Application]. */
fun Application.module() {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
    }
}
