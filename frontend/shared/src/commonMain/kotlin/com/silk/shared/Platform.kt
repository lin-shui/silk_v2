package com.silk.shared

import io.ktor.client.HttpClient

expect class Platform() {
    val name: String
}

expect fun getPlatform(): Platform

expect fun createPlatformHttpClient(): HttpClient
