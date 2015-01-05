/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */

var ws = new WebSocket('ws://' + location.host + '/groupcall');
var participants = {};
var name;
var rtcPeer;
var localstream;
var sdpConstraints = {
    'mandatory': {
    'OfferToReceiveAudio': true,
    'OfferToReceiveVideo': false
  }
};
var remoteAudio;

window.onbeforeunload = function() {
	ws.close();
};

ws.onmessage = function(message) {
	var parsedMessage = JSON.parse(message.data);
	console.info('Received message: ' + message.data);

	switch (parsedMessage.id) {
	case 'existingParticipants':
		onExistingParticipants(parsedMessage);
		break;
	case 'newParticipantArrived':
		onNewParticipant(parsedMessage);
		break;
	case 'participantLeft':
		onParticipantLeft(parsedMessage);
		break;
	case 'receiveVideoAnswer':
		receiveVideoResponse(parsedMessage);
		break;
	case 'answer_sdp':
	    onAnswerSdp(parsedMessage.answer_sdp);
	    break;
	default:
		console.error('Unrecognized message', parsedMessage);
	}
}

function register() {
	name = document.getElementById('name').value;
	var room = document.getElementById('roomName').value;

	document.getElementById('room-header').innerText = 'ROOM ' + room;
	document.getElementById('join').style.display = 'none';
	document.getElementById('room').style.display = 'block';

    remoteAudio = document.getElementById('remote_audio');

    var servers = null;
    var pcConstraints = {
        'optional': []
      };
    rtcPeer = new RTCPeerConnection(servers, pcConstraints);
    console.log('Created local peer connection object pc1');
    rtcPeer.onicecandidate =  function(e) {
        // candidate exists in e.candidate
  		if (e.candidate)
  			return;

  		var offerSdp = rtcPeer.localDescription.sdp;
		trace('Invoking SDP offer callback function');
	    var message = {
	    	id : 'joinRoom',
	    	name : name,
	    	room : room,
	    	sdpOffer : offerSdp
	    }
	    sendMessage(message);
    }

//    rtcPeer.onaddstream = function(e) {
//        attachMediaStream(remoteAudio, e.stream);
//    };

    getUserMedia({
        audio: true,
        video: false
    }, function(stream) {
        trace('Received local stream');
        // Call the polyfill wrapper to attach the media stream to this element.
        localstream = stream;
        var audioTracks = localstream.getAudioTracks();
        if (audioTracks.length > 0) {
            trace('Using Audio device: ' + audioTracks[0].label);
        }
        rtcPeer.addStream(localstream);
        trace('Adding Local Stream to peer connection');

        rtcPeer.createOffer(function(offer) {
            console.log('Created SDP offer');
            rtcPeer.setLocalDescription(offer, function() {
                console.log('Local description set');
            });
        }, function(e){
            console.log(e);
        });
    }, function(e) {
       alert('getUserMedia() error: ' + e.name);
    });

	var participant = new Participant(name);
	participants[name] = participant;
}

function onNewParticipant(request) {
	receiveVideo(request.name);
}

function receiveVideoResponse(result) {
	participants[result.name].rtcPeer.processSdpAnswer(result.sdpAnswer);
}

function onAnswerSdp(sdpAnswer) {
    var answer = new RTCSessionDescription({
		type : 'answer',
		sdp : sdpAnswer,
	});

	console.log('SDP answer received, setting remote description');
	rtcPeer.setRemoteDescription(answer, function() {
	    trace('Set remote description completed')
    	var stream = rtcPeer.getRemoteStreams()[0];
        attachMediaStream(remoteAudio, stream);
//    	remoteAudio.src = URL.createObjectURL(stream);
	}, function(e){
	    trace('error: ' + e);
	} );
}

function callResponse(message) {
	if (message.response != 'accepted') {
		console.info('Call not accepted by peer. Closing call');
		stop();
	} else {
		webRtcPeer.processSdpAnswer(message.sdpAnswer);
	}
}

function onExistingParticipants(msg) {
	console.log(name + " registered in room " + room);
//	var participant = new Participant(name);
//	participants[name] = participant;
//	var video = participant.getVideoElement();
//	participant.rtcPeer = kurentoUtils.WebRtcPeer.startSendOnly(video,
//			participant.offerToReceiveVideo.bind(participant), null,
//			constraints);
//	msg.data.forEach(receiveVideo);
}

function leaveRoom() {
	sendMessage({
		id : 'leaveRoom'
	});

	for ( var key in participants) {
		participants[key].dispose();
	}

	document.getElementById('join').style.display = 'block';
	document.getElementById('room').style.display = 'none';

	ws.close();
}

function receiveVideo(sender) {
	var participant = new Participant(sender);
	participants[sender] = participant;
//	var video = participant.getVideoElement();
//	participant.rtcPeer = kurentoUtils.WebRtcPeer.startRecvOnly(video,
//			participant.offerToReceiveVideo.bind(participant), {video:false,audio:true});
}

function onParticipantLeft(request) {
	console.log('Participant ' + request.name + ' left');
	var participant = participants[request.name];
	participant.dispose();
	delete participants[request.name];
}

function sendMessage(message) {
	var jsonMessage = JSON.stringify(message);
	console.log('Senging message: ' + jsonMessage);
	ws.send(jsonMessage);
}
