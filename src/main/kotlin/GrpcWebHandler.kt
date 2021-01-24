package com.github.gordonmu.ktor_grpcweb

import com.google.protobuf.ByteString
import com.google.protobuf.UnsafeByteOperations
import io.grpc.InternalMetadata
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import java.io.FilterOutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.time.Duration
import java.util.*

internal val CONTENTTYPE_GRPC_WEB: ContentType = ContentType.parse("application/grpc-web")
internal val CONTENTTYPE_GRPC_WEB_PROTO: ContentType = ContentType.parse("application/grpc-web+proto")
internal val CONTENTTYPE_GRPC_WEB_TEXT: ContentType = ContentType.parse("application/grpc-web-text")
internal val CONTENTTYPE_GRPC_WEB_TEXT_PROTO: ContentType = ContentType.parse("application/grpc-web-text+proto")
internal val MARKER_DATA: Byte = 0 // 0b00
internal val MARKER_TRAILER: Byte = -128 // 0b10

fun Route.handleGrpcWebCalls(channel: io.grpc.Channel) {
    options("{service}/{method}") {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.AccessControlAllowMethods, "POST")
        call.response.header(
            HttpHeaders.AccessControlAllowHeaders,
            call.request.headers.getAll(HttpHeaders.AccessControlRequestHeaders)?.joinToString(", ")
                ?: ""
        )
        call.response.header(HttpHeaders.AccessControlMaxAge, Duration.ofDays(1).seconds)
        call.respond(HttpStatusCode.NoContent)
    }
    post("{service}/{method}") {
        val service = call.parameters["service"]
        val method = call.parameters["method"]
        val fullMethodName = "$service/$method"
        when (call.request.contentType()) {
            CONTENTTYPE_GRPC_WEB -> handleGrpcWebCall(channel, fullMethodName)
            CONTENTTYPE_GRPC_WEB_PROTO -> handleGrpcWebCall(channel, fullMethodName)
            CONTENTTYPE_GRPC_WEB_TEXT -> handleGrpcWebTextCall(channel, fullMethodName)
            CONTENTTYPE_GRPC_WEB_TEXT_PROTO -> handleGrpcWebTextCall(channel, fullMethodName)
            else -> {
                call.respondText(status = HttpStatusCode.BadRequest) { "Unsupported content-type ${call.request.contentType()}, grpc-web expected" }
            }
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleGrpcWebCall(
    channel: io.grpc.Channel,
    fullMethodName: String
) =
    handleGrpcWebCall(channel, fullMethodName, false)

private suspend fun PipelineContext<Unit, ApplicationCall>.handleGrpcWebTextCall(
    channel: io.grpc.Channel,
    fullMethodName: String
) =
    handleGrpcWebCall(channel, fullMethodName, true)

private suspend fun PipelineContext<Unit, ApplicationCall>.handleGrpcWebCall(
    grpcChannel: io.grpc.Channel,
    fullMethodName: String,
    text: Boolean
) {

    val requestFrame = call.request.receiveChannel().readCompletely(text)
    val hbytes = requestFrame.substring(0, 5).asReadOnlyByteBuffer()
    hbytes.get() // value can be ignored
    val bodyLength = hbytes.getInt()
    val requestProto = requestFrame.substring(5, 5 + bodyLength)
    val md = call.request.headers.toMetadata()

    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.respond(HttpStatusCode.OK, object : OutgoingContent.WriteChannelContent() {
        override val contentType = if (text) CONTENTTYPE_GRPC_WEB_TEXT_PROTO else CONTENTTYPE_GRPC_WEB_PROTO
        override suspend fun writeTo(channel: ByteWriteChannel) {
            val respFlow = grpcChannel.call(fullMethodName, requestProto, md, Int.MAX_VALUE)
            respFlow.onCompletion {
                when (it) {
                    null ->
                        channel.writeFully(if (text) okTextTrailerBytes else okTrailerBytes)
                    is StatusRuntimeException ->
                        channel.writeGrpcWebPacket(grpcWebTrailers(it.status, it.trailers ?: Metadata()), text)
                    else ->
                        channel.writeGrpcWebPacket(
                            grpcWebTrailers(
                                Status.INTERNAL.withDescription(it.toString()),
                                Metadata()
                            ), text
                        )
                }
                channel.close()
            }.collect {
                val packet = grpcWebPacket(it)
                channel.writeGrpcWebPacket(packet, text)
                channel.flush()
            }
        }
    })
}

private suspend fun ByteWriteChannel.writeGrpcWebPacket(packet: ByteReadPacket, text: Boolean) {
    if (text) {
        val encoder = Base64.getEncoder()
        // workaround because the encoded outputstream must be closed but we cant close the underlying stream yet
        val fos = object : FilterOutputStream(toOutputStream()) {
            override fun close() {
                // intentionally ignoring close here
            }
        }
        val os = encoder.wrap(fos)
        os.writePacket(packet)
        os.close()
    } else {
        writePacket(packet)
    }
}

private fun grpcWebPacket(bs: ByteString): ByteReadPacket = buildPacket {
    writeByte(MARKER_DATA)
    writeInt(bs.size())
    bs.asReadOnlyByteBufferList().forEach {
        writeFully(it)
    }
}

private val MKEY_GRPC_STATUS = Metadata.Key.of("grpc-status", Metadata.ASCII_STRING_MARSHALLER)
private val MKEY_GRPC_MESSAGE = Metadata.Key.of("grpc-message", Metadata.ASCII_STRING_MARSHALLER)

//grpc-status:0
private val okTextTrailerBytes = "gAAAAA9ncnBjLXN0YXR1czowDQo=".toByteArray()
private val okTrailerBytes = Base64.getDecoder().decode(okTextTrailerBytes)

private fun grpcWebTrailers(status: io.grpc.Status, metadata: io.grpc.Metadata): ByteReadPacket = buildPacket {
    metadata.put(MKEY_GRPC_STATUS, status.code.value().toString())
    status.description?.let {
        metadata.put(MKEY_GRPC_MESSAGE, it)
    }

    val mds = InternalMetadata.serialize(metadata)
    var size = mds.fold(0) { i, bs -> i + bs.size }
    size += (mds.size / 2) * 3 // add 3 chars for each pair :, \r, \n
    writeByte(MARKER_TRAILER)
    writeInt(size)
    for (i in mds.indices step 2) {
        writeFully(ByteBuffer.wrap(mds[i]))
        append(':')
        writeFully(ByteBuffer.wrap(mds[i + 1]))
        append('\r')
        append('\n')
    }
}

private suspend fun ByteReadChannel.readCompletely(text: Boolean): ByteString {
    var chunkSize = 256
    val maxSize = 8 * 1024
    var res = ByteString.EMPTY
    val decoder = Base64.getDecoder()
    var buf = ByteBuffer.allocate(chunkSize)
    while (true) {
        val read = readAvailable(buf)
        if (read < 0) {
            // some bytes might be left in buf
            if (buf.position() > 0) {
                (buf as Buffer).flip()
                res = res.concat(UnsafeByteOperations.unsafeWrap(decoder.decode(buf)))
            }
            break
        }
        chunkSize = (chunkSize * 2).coerceAtMost(maxSize)
        (buf as Buffer).flip() // cast needed for some java compat issues
        buf = if (text) {
            // limit the buf to b64 decodable groups of 4 bytes
            val oldLimit = buf.limit()
            val groupLimit = buf.limit() and 0x3.inv() // round down to nearest number dividable by 4
            buf.limit(groupLimit)
            res = res.concat(UnsafeByteOperations.unsafeWrap(decoder.decode(buf)))
            buf.limit(oldLimit) // now some 0-3 bytes might be left, dump them in the next buffer
            val newBuf = ByteBuffer.allocate(chunkSize)
            newBuf.put(buf)
            newBuf
        } else {
            res = res.concat(UnsafeByteOperations.unsafeWrap(buf))
            ByteBuffer.allocate(chunkSize)
        }
    }
    return res
}
