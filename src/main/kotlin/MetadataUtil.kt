package com.github.gordonmu.ktor_grpcweb

import io.ktor.http.*

internal fun Headers.toMetadata(): io.grpc.Metadata {
    val md = io.grpc.Metadata()
    this.entries().forEach { entry ->
        if (entry.key.endsWith(io.grpc.Metadata.BINARY_HEADER_SUFFIX)) {
            val k = io.grpc.Metadata.Key.of(entry.key, io.grpc.Metadata.BINARY_BYTE_MARSHALLER)
            entry.value.forEach {
                md.put(k, it.toByteArray(Charsets.US_ASCII))
            }
        } else {
            val k = io.grpc.Metadata.Key.of(entry.key, io.grpc.Metadata.ASCII_STRING_MARSHALLER)
            entry.value.forEach {
                md.put(k, it)
            }
        }
    }
    return md
}