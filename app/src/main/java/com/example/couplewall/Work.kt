package com.example.couplewall

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Shared background executor for rendering and disk work. */
object Work {
    val io: ExecutorService = Executors.newFixedThreadPool(2)
}
