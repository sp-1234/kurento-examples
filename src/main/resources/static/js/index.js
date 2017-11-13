/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

var ws = new WebSocket('ws://' + location.host + '/call');
var video;
var webRtcPeer;

window.onload = function() {
    console = new Console();
    video = document.getElementById('video');
    disableStopButton();
}

function getStreamId() {
    return $(streamId).val();
}

function presenter() {
    if (!webRtcPeer) {
        var options = {
            localVideo : video,
            onicecandidate : onIceCandidate
        }
        webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendonly(options,
                function(error) {
                    if (error) {
                        return console.error(error);
                    }
                    webRtcPeer.generateOffer(onOffer('presenter'));
                });

        enableStopButton();
    }
}

function viewer() {
    if (!webRtcPeer) {
        var options = {
            remoteVideo : video,
            onicecandidate : onIceCandidate
        }
        webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerRecvonly(options,
                function(error) {
                    if (error) {
                        return console.error(error);
                    }
                    this.generateOffer(onOffer('viewer'));
                });

        enableStopButton();
    }
}

function onOffer(replyKind) {
    return function(error, offerSdp) {
        if (error)
            return console.error('Error generating the offer');
        console.info('Invoking SDP offer callback function ' + location.host);
        var message = {
            kind: replyKind,
            streamId: getStreamId(),
            sdpOffer: offerSdp
        }
        sendMessage(message);
    }
}

window.onbeforeunload = function() {
    ws.close();
}

ws.onmessage = function(message) {
    var parsedMessage = JSON.parse(message.data);
    console.info('Received message: ' + message.data);

    switch (parsedMessage.kind) {
        case 'presenterResponse':
        case 'viewerResponse':
            response(parsedMessage);
            break;
        case 'iceCandidate':
            webRtcPeer.addIceCandidate(parsedMessage.candidate, function(error) {
                if (error)
                    return console.error('Error adding candidate: ' + error);
            });
            break;
        case 'stopCommunication':
            dispose();
            break;
        default:
            console.error('Unrecognized message', parsedMessage);
    }
}

function response(message) {
    if (message.response != 'accepted') {
        var errorMsg = message.message ? message.message : 'Unknown error';
        console.info('Call not accepted for the following reason: ' + errorMsg);
        dispose();
    } else {
        webRtcPeer.processAnswer(message.sdpAnswer, function(error) {
            if (error)
                return console.error(error);
        });
    }
}

function onIceCandidate(candidate) {
    console.log("Local candidate" + JSON.stringify(candidate));

    var message = {
        kind: 'onIceCandidate',
        candidate: candidate
    };
    sendMessage(message);
}

function stop() {
    var message = {
        kind: 'stop'
    };
    sendMessage(message);
    dispose();
}

function dispose() {
    if (webRtcPeer) {
        webRtcPeer.dispose();
        webRtcPeer = null;
    }
    disableStopButton();
}

function disableStopButton() {
    enableButton('#presenter', 'presenter()');
    enableButton('#viewer', 'viewer()');
    disableButton('#stop');
}

function enableStopButton() {
    disableButton('#presenter');
    disableButton('#viewer');
    enableButton('#stop', 'stop()');
}

function disableButton(id) {
    $(id).attr('disabled', true);
    $(id).removeAttr('onclick');
}

function enableButton(id, functionName) {
    $(id).attr('disabled', false);
    $(id).attr('onclick', functionName);
}

function sendMessage(message) {
    var jsonMessage = JSON.stringify(message);
    console.log('Senging message: ' + jsonMessage);
    ws.send(jsonMessage);
}


/**
 * Lightbox utility (to display media pipeline image in a modal dialog)
 */
$(document).delegate('*[data-toggle="lightbox"]', 'click', function(event) {
    event.preventDefault();
    $(this).ekkoLightbox();
});
