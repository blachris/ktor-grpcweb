package com.github.blachris.ktor_grpcweb

import com.google.protobuf.ByteString
import io.grpc.*
import io.grpc.MethodDescriptor
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

fun handleTestCall(scall: ServerCall<ByteString, ByteString>, headers: Metadata): ServerCall.Listener<ByteString> {
    scall.sendHeaders(Metadata())
    scall.request(1)
    return object : ServerCall.Listener<ByteString>() {
        override fun onMessage(message: ByteString) {
            scall.sendMessage(message)
            scall.close(Status.OK, Metadata())
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrpcWebTests {
    val handlerRegistry = object : HandlerRegistry() {
        override fun lookupMethod(methodName: String, authority: String?): ServerMethodDefinition<*, *>? =
            ServerMethodDefinition.create(
                MethodDescriptor.newBuilder<ByteString, ByteString>()
                    .setFullMethodName(methodName)
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setRequestMarshaller(MARSHALLER_INSTANCE)
                    .setResponseMarshaller(MARSHALLER_INSTANCE)
                    .build(),
                ::handleTestCall
            )
    }
    val grpcServer = InProcessServerBuilder.forName("test").fallbackHandlerRegistry(handlerRegistry).build()
    val grpcServerChannel = InProcessChannelBuilder.forName("test").build()

    val client = HttpClient(CIO)

    @BeforeAll
    fun setup() {
        grpcServer.start()
    }

    @AfterAll
    fun close() {
        client.close()
        grpcServerChannel.shutdownNow()
        grpcServer.shutdownNow()
    }

    // test payload:
    // b64 encoded 520 bytes: 5 header bytes and 515 payload
    private val requestTextLong =
        "AAAAAgMKgAQwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZg=="

    // b64 encoded 20 bytes: 5 trailer header and 15 trailer bytes: "grpc-status:0\r\n"
    private val standardTrailer = "gAAAAA9ncnBjLXN0YXR1czowDQo="

    private val totalTesponseTextLong = requestTextLong + standardTrailer

    private val requestTextShort = "AAAAAA8KDTAxMjM0NTY3ODlhYmM="
    private val totalTesponseTextShort = requestTextShort + standardTrailer

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class `when using netty server` {
        val webServer = embeddedServer(Netty, 40927) {
            routing {
                route("grpcweb") {
                    handleGrpcWebCalls(grpcServerChannel)
                }
            }
        }.apply { start() }

        @AfterAll
        fun close() {
            webServer.stop(200, 500)
        }

        @Test
        fun `test unary grpc web call long payload`() {
            runBlocking {
                val resp: HttpResponse = client.post("http://localhost:40927/grpcweb/tservice/tmethod") {
                    accept(CONTENTTYPE_GRPC_WEB_TEXT)
                    contentType(CONTENTTYPE_GRPC_WEB_TEXT)
                    body = requestTextLong
                }
                assertThat(resp.status).isEqualTo(HttpStatusCode.OK)

                val totalResponse = resp.readText()
                assertThat(totalResponse).isEqualTo(totalTesponseTextLong)
            }
        }

        @Test
        fun `test unary grpc web call short payload`() {
            runBlocking {
                val resp: HttpResponse = client.post("http://localhost:40927/grpcweb/tservice/tmethod") {
                    accept(CONTENTTYPE_GRPC_WEB_TEXT)
                    contentType(CONTENTTYPE_GRPC_WEB_TEXT)
                    // lets also try trimming the padding to test decoder robustness
                    body = requestTextShort.substring(0, requestTextShort.length - 1)
                }
                assertThat(resp.status).isEqualTo(HttpStatusCode.OK)

                val totalResponse = resp.readText()
                assertThat(totalResponse).isEqualTo(totalTesponseTextShort)
            }
        }
    }
}