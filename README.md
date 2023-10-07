![Java CI](https://github.com/blachris/ktor-grpcweb/workflows/Java%20CI/badge.svg)
 [ ![Download](https://api.bintray.com/packages/blachris/jvm/ktor-grpcweb/images/download.svg) ](https://bintray.com/blachris/jvm/ktor-grpcweb/_latestVersion)

# Embedded gRPC Web Proxy for Ktor
An embedded [gRPC Web](https://github.com/grpc/grpc-web) proxy for Java/Kotlin [gRPC](https://grpc.io/) servers 
using [Ktor](https://ktor.io/) as HTTP server.

GRPC is a versatile RPC protocol with good support for many programming languages.
Javascript applications running in a browser, however, cannot use the binary gRPC protocol directly, instead
the gRPC Web protocol can be used, which offers some features of binary gRPC and uses regular HTTP/1 as transport.

To access a regular gRPC server via the gRPC Web protocol, a proxy is needed to convert protocols.
There are only a few such gRPC Web proxies available, a common one is [envoy](https://www.envoyproxy.io/).

In small setups, a dedicated gRPC Web proxy can be avoided with an embedded proxy such as this.
This project provides a handler that integrates into the HTTP server Ktor that proxies between gRPC Web calls and 
an internal or external binary gRPC server.

## Features

* Both gRPC Web and gRPC Web Text protocols are supported
* CORS support, server responds to CORS preflight OPTION calls and sets headers to allow CORS calls
* Proxied gRPC server can be in-process or external
* HTTPS provided by Ktor

Not supported:
* Routing to multiple gRPC servers

## Usage

~~~
val channel = InProcessChannelBuilder.forName("my-server").build()
val webServer = embeddedServer(Netty, 40927) {
    routing {
        route("grpcweb") {
            handleGrpcWebCalls(channel)
        }
    }
}
webServer.start()
~~~
