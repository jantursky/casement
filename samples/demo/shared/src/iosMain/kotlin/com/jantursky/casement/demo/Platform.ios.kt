package com.jantursky.casement.demo

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun demoHttpClient(): HttpClient = HttpClient(Darwin)

internal actual fun serverHost(): String = "localhost"
