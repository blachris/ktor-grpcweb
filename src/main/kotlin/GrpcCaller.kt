package com.github.gordonmu.ktor_grpcweb

import com.google.protobuf.ByteString
import io.grpc.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.lang.Integer.max
import java.util.concurrent.CancellationException

fun io.grpc.Channel.call(
    fullMethodName: String,
    request: ByteString,
    metadata: io.grpc.Metadata = io.grpc.Metadata(),
    bufferSize: Int = 32
): Flow<ByteString> {
    val modBufferSize = bufferSize.coerceIn(0..1000000)
    val mdescriptor = MethodDescriptor.newBuilder<ByteString, ByteString>().setFullMethodName(fullMethodName)
        .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
        .setRequestMarshaller(MARSHALLER_INSTANCE)
        .setResponseMarshaller(MARSHALLER_INSTANCE)
        .build()
    val call = newCall(mdescriptor, CallOptions.DEFAULT)
    val callListener = ClientCallListener(modBufferSize)

    return flow {
        call.start(callListener, metadata)
        call.sendMessage(request)
        call.request(modBufferSize)
        call.halfClose()
        val requestInterval = max(1, modBufferSize / 2)
        var i = 0
        for (r in callListener.responses) {
            emit(r)
            if (++i == requestInterval) {
                call.request(requestInterval)
                i = 0
            }
        }
    }.onCompletion { err ->
        if (err is CancellationException)
            call.cancel("canceled", err)
    }
}

private class ClientCallListener(channelSize: Int) : ClientCall.Listener<ByteString>() {
    private val respChannel: Channel<ByteString> = Channel(channelSize)
    val responses: ReceiveChannel<ByteString> = respChannel

    override fun onMessage(message: ByteString) {
        respChannel.sendBlocking(message)
    }

    override fun onHeaders(headers: io.grpc.Metadata) {
    }

    override fun onClose(status: Status, trailers: io.grpc.Metadata) {
        if (status.isOk)
            respChannel.close()
        else
            respChannel.close(status.asRuntimeException(trailers))
    }
}

internal class PassthroughMarshaller : MethodDescriptor.Marshaller<ByteString> {
    override fun stream(value: ByteString): InputStream {
        return value.newInput()
    }

    override fun parse(stream: InputStream): ByteString {
        return ByteString.readFrom(stream)
    }
}

internal val MARSHALLER_INSTANCE = PassthroughMarshaller()