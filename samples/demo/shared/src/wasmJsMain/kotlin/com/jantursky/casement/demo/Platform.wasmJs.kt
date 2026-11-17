package com.jantursky.casement.demo

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

internal actual fun demoHttpClient(): HttpClient = HttpClient(Js)

internal actual fun serverHost(): String = "localhost"
