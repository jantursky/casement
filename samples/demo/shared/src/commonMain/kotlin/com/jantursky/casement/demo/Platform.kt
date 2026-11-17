package com.jantursky.casement.demo

import io.ktor.client.HttpClient

/** An HTTP client with the engine of this platform. */
internal expect fun demoHttpClient(): HttpClient

/** How this platform reaches the machine the example servers run on. The Android emulator has its own alias. */
internal expect fun serverHost(): String
