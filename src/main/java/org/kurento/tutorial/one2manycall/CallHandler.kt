package org.kurento.tutorial.one2manycall

import ch.qos.logback.classic.Level
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.kurento.client.*
import org.kurento.jsonrpc.JsonUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

fun WebSocketSession.sendMessage(message: JsonObject) {
    sendMessage(TextMessage("$message"))
}

class BroadcastingStream(val presenter: WebRTCSession, val pipeline: MediaPipeline) {
    val viewers = ConcurrentHashMap<String, WebRTCSession>() // websocket id -> …
    fun getUserByWsId(wsId: String): WebRTCSession? {
        return if (presenter.ws.id == wsId) {
            presenter
        } else {
            viewers[wsId]
        }
    }
}

class CallHandler : TextWebSocketHandler() {
    companion object {
        private val log = LoggerFactory.getLogger(CallHandler::class.java)

        init {
            (log as ch.qos.logback.classic.Logger).level = Level.DEBUG
        }

        private val gson = GsonBuilder().create()
    }

    private val streams = ConcurrentHashMap<String, BroadcastingStream>()
    private val streamsByWs = ConcurrentHashMap<String, String>() // websocket id -> stream id

    @Autowired
    private lateinit var kurento: KurentoClient

    @Throws(Exception::class)
    public override fun handleTextMessage(ws: WebSocketSession?, message: TextMessage?) {
        val jsonMessage = gson.fromJson(message!!.payload, JsonObject::class.java)
        val wsId = ws!!.id
        log.debug("Incoming message from session '{}': {}", wsId, jsonMessage)

        try {
            val messageKind = jsonMessage["kind"].asString
            when (messageKind) {
                "presenter" -> presenter(ws, jsonMessage)
                "presenterSDPAnswer" -> onPresenterSDPAnswer(ws, jsonMessage["sdp"].asString)

                "viewer" -> viewer(ws, jsonMessage)

                "onIceCandidate" -> run {
                    val streamId = streamsByWs[wsId] ?: return@run
                    val stream = streams[streamId] ?: return@run
                    val user = stream.getUserByWsId(wsId)

                    val candidateJs = jsonMessage["candidate"].asJsonObject
                    val candidate = IceCandidate(
                            candidateJs["candidate"].asString,
                            candidateJs["sdpMid"].asString,
                            candidateJs["sdpMLineIndex"].asInt)
                    user?.webRtcEndpoint?.addIceCandidate(candidate, simpleContinuation {})
                }
                "stop" -> stop(ws)
                else -> {
                    throw IllegalStateException("illegal message kind '$messageKind'")
                }
            }
        } catch (e: Throwable) {
            onReplyFailed(e, ws)
        }
    }

    @Throws(IOException::class)
    private fun onReplyFailed(throwable: Throwable, ws: WebSocketSession) {
        stop(ws)
        log.error(throwable.message, throwable)
    }

    @Synchronized
    @Throws(IOException::class)
    private fun presenter(ws: WebSocketSession, jsonMessage: JsonObject) {
        val streamId = jsonMessage["streamId"].asString

        val stream = streams.putIfAbsentLazy(streamId, {
            BroadcastingStream(
                    WebRTCSession(ws),
                    kurento.createMediaPipeline()
            )
        })
        if (stream == null) {
            ws.sendMessage(JsonObject().apply {
                addProperty("kind", "presenterResponse")
                addProperty("response", "rejected")
                addProperty("message", "Stream with id='$streamId' already exists")
            })
            return
        }

        streamsByWs[ws.id] = streamId

        val presenterWebRTCEndpoint = WebRtcEndpoint.Builder(stream.pipeline).build()
        stream.presenter.webRtcEndpoint = presenterWebRTCEndpoint

        presenterWebRTCEndpoint.gatherCandidates(simpleContinuation {
            presenterWebRTCEndpoint.generateOffer(simpleContinuation { offer: String ->
                synchronized(ws) {
                    ws.sendMessage(JsonObject().apply {
                        addProperty("kind", "presenterResponse")
                        addProperty("response", "accepted")
                        addProperty("sdpOffer", offer)
                    })
                }
            })
        })
    }

    @Synchronized
    private fun onPresenterSDPAnswer(ws: WebSocketSession, sdpAnswer: String) {
        val streamKey = streamsByWs[ws.id]
        val stream = streams[streamKey]!!
        val presenterWebRTCEndpoint = stream.presenter.webRtcEndpoint!!
        presenterWebRTCEndpoint.processAnswer(sdpAnswer, simpleContinuation { })
    }

    @Synchronized
    private fun viewer(ws: WebSocketSession, jsonMessage: JsonObject) {
        val streamId = jsonMessage["streamId"].asString
        val stream = streams[streamId]
        if (stream == null) {
            ws.sendMessage(JsonObject().apply {
                addProperty("kind", "viewerResponse")
                addProperty("response", "rejected")
                addProperty("message", "Stream with id='$streamId' doesn't exist")
            })
            return
        }

        val viewer = stream.viewers.putIfAbsentLazy(ws.id, {
            WebRTCSession(ws)
        })

        if (viewer == null) {
            ws.sendMessage(JsonObject().apply {
                addProperty("kind", "viewerResponse")
                addProperty("response", "rejected")
                addProperty("message", "You are already viewing this stream in this session.")
            })
            return
        }
        streamsByWs[ws.id] = streamId

        val viewerWebRTCEndpoint = WebRtcEndpoint.Builder(stream.pipeline).build()

        viewerWebRTCEndpoint.addIceCandidateFoundListener { event ->
            val candidate = event.candidate
            synchronized(ws) {
                ws.sendMessage(JsonObject().apply {
                    addProperty("kind", "iceCandidate")
                    add("candidate", JsonUtils.toJsonObject(candidate))
                })
            }
        }

        viewer.webRtcEndpoint = viewerWebRTCEndpoint
        stream.presenter.webRtcEndpoint!!.connect(viewerWebRTCEndpoint)

        val sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").asString

        viewerWebRTCEndpoint.processOffer(sdpOffer, simpleContinuation({ sdpAnswer ->
            ws.sendMessage(JsonObject().apply {
                addProperty("kind", "viewerResponse")
                addProperty("response", "accepted")
                addProperty("sdpAnswer", sdpAnswer)
            })
            viewerWebRTCEndpoint.gatherCandidates()
        }))
    }

    @Synchronized
    @Throws(IOException::class)
    private fun stop(ws: WebSocketSession) {
        val wsId: String = ws.id
        val streamId = streamsByWs.remove(wsId) ?: return
        val stream = streams[streamId] ?: return

        if (stream.presenter.ws.id == wsId) {
            streams.remove(streamId)
            for (viewer in stream.viewers.values) {
                val viewerWs = viewer.ws
                streamsByWs.remove(viewerWs.id)
                viewerWs.sendMessage(JsonObject().apply {
                    addProperty("kind", "stopCommunication")
                })
            }
            log.info("Releasing media pipeline in stream '$streamId'")
            stream.pipeline.release()
        } else {
            val viewer = stream.viewers.remove(wsId) ?: return
            viewer.webRtcEndpoint?.release()
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession?, status: CloseStatus?) {
        stop(session!!)
    }
}
