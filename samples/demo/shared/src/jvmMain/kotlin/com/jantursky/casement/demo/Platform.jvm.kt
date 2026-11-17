package com.jantursky.casement.demo

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

internal actual fun demoHttpClient(): HttpClient = HttpClient(CIO)

internal actual fun serverHost(): String = "localhost"
