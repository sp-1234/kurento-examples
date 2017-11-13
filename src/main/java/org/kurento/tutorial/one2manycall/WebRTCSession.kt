package org.kurento.tutorial.one2manycall

import org.kurento.client.WebRtcEndpoint
import org.springframework.web.socket.WebSocketSession

class WebRTCSession(
        val ws: WebSocketSession) {
    var webRtcEndpoint: WebRtcEndpoint? = null
}
