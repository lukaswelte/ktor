package io.ktor.client.engine.cio

import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.content.*
import io.ktor.network.tls.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.io.*
import java.net.*
import java.util.concurrent.atomic.*

internal class Endpoint(
        host: String,
        port: Int,
        private val secure: Boolean,
        private val dispatcher: CoroutineDispatcher,
        private val config: CIOEngineConfig,
        private val connectionFactory: ConnectionFactory,
        private val onDone: () -> Unit
) : Closeable {
    private val tasks: Channel<ConnectionRequestTask> = Channel(Channel.UNLIMITED)
    private val deliveryPoint: Channel<ConnectionRequestTask> = Channel()

    private val MAX_ENDPOINT_IDLE_TIME = 2 * config.endpoint.connectTimeout

    @Volatile
    private var connectionsHolder: Int = 0

    private val address = InetSocketAddress(host, port)

    private val postman = launch(dispatcher, start = CoroutineStart.LAZY) {
        try {
            while (true) {
                val task = withTimeoutOrNull(MAX_ENDPOINT_IDLE_TIME) {
                    tasks.receive()
                }

                if (task == null) {
                    onDone()
                    tasks.close()
                    continue
                }

                if (deliveryPoint.offer(task)) continue

                val connections = Connections.get(this@Endpoint)

                val connect: suspend () -> Boolean = {
                    withTimeoutOrNull(config.endpoint.connectTimeout) { newConnection() } != null
                }

                try {
                    if (connections < config.endpoint.maxConnectionsPerRoute && !tryExecute(config.endpoint.connectRetryAttempts, connect)) {
                        task.continuation.resumeWithException(ConnectTimeout(task.request))
                        continue
                    }
                } catch (cause: Throwable) {
                    task.continuation.resumeWithException(cause)
                    throw cause
                }

                deliveryPoint.send(task)
            }
        } catch (_: ClosedReceiveChannelException) {
        } finally {
            deliveryPoint.close()
        }
    }

    suspend fun execute(request: CIOHttpRequest, content: OutgoingContent): CIOHttpResponse =
            suspendCancellableCoroutine {
                val task = ConnectionRequestTask(request, content, it)
                tasks.offer(task)
            }

    private suspend fun newConnection() {
        Connections.incrementAndGet(this)
        val socket = connectionFactory.connect(address).let {
            if (secure) it.tls(config.https.trustManager, address.hostName, dispatcher) else it
        }

        ConnectionPipeline(
                dispatcher,
                config.endpoint.keepAliveTime, config.endpoint.pipelineMaxSize,
                socket,
                deliveryPoint
        ).apply {
            pipelineContext.invokeOnCompletion {
                connectionFactory.release()
                Connections.decrementAndGet(this@Endpoint)
            }
        }
    }

    override fun close() {
        tasks.close()
    }

    init {
        postman.start()
    }

    companion object {
        private val Connections =
                AtomicIntegerFieldUpdater.newUpdater(Endpoint::class.java, Endpoint::connectionsHolder.name)
    }
}

class ConnectTimeout(val request: HttpRequest) : Exception("Connect timed out")
