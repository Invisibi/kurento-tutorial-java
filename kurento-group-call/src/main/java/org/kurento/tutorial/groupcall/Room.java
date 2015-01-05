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
package org.kurento.tutorial.groupcall;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Ivan Gracia (izanmail@gmail.com)
 * @since 4.3.1
 */
public class Room implements Closeable {
    private final Logger log = LoggerFactory.getLogger(Room.class);

    private final ConcurrentMap<String, UserSession> participants = new ConcurrentHashMap<>();
    private final MediaPipeline pipeline;
    private final String name;
    private final Composite composite;
    private RecorderEndpoint recorderEndpoint;
    private HubPort hubPort;

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    public Room(String roomName, MediaPipeline pipeline) {
        this.name = roomName;
        this.pipeline = pipeline;
        this.composite = new Composite.Builder(pipeline).build();
        log.info("ROOM {} has been created", roomName);
    }

    @PreDestroy
    private void shutdown() {
        this.close();
    }

    public UserSession join(String userName, WebSocketSession session, String sdpOffer)
            throws IOException {
        log.info("ROOM {}: adding participant {}", getName(), userName);

        final UserSession participant = new UserSession(userName, this.name, session, this.pipeline, this.composite);
        if (participants.size() == 1) {
            // first user join
            log.info("ROOM {}: Start recording", getName());
            this.hubPort = new HubPort.Builder(composite).build();
            this.recorderEndpoint = new RecorderEndpoint.Builder(pipeline, "file:///tmp/" + getName() + ".webm")
                    .withMediaProfile(MediaProfileSpecType.MP4)
                    .build();
            hubPort.connect(recorderEndpoint);
            recorderEndpoint.connect(hubPort);
            recorderEndpoint.record();
        }
        joinRoom(participant);
        participants.put(participant.getName(), participant);

        WebRtcEndpoint webRtcEndpoint = participant.getWebRtcEndpoint();
        String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);
        final JsonObject answerMessage = new JsonObject();
        answerMessage.addProperty("id", "answer_sdp");
        answerMessage.addProperty("answer_sdp", sdpAnswer);
        participant.sendMessage(answerMessage);

        sendParticipantNames(participant);
        return participant;
    }

    public void leave(UserSession user) throws IOException {
        log.debug("PARTICIPANT {}: Leaving room {}", user.getName(), this.name);
//        recorderEndpoint.stop();
        pipeline.release();
        this.removeParticipant(user.getName());
        user.close();
    }

    /**
     * @param participant
     * @throws IOException
     */
    private Collection<String> joinRoom(UserSession newParticipant)
            throws IOException {
        final JsonObject newParticipantMsg = new JsonObject();
        newParticipantMsg.addProperty("id", "newParticipantArrived");
        newParticipantMsg.addProperty("name", newParticipant.getName());

        final List<String> participantsList = new ArrayList<>(participants
                .values().size());
        log.debug(
                "ROOM {}: notifying other participants of new participant {}",
                name, newParticipant.getName());

        for (final UserSession participant : participants.values()) {
            try {
                participant.sendMessage(newParticipantMsg);

                // connect
//                WebRtcEndpoint existPeer = participant.getWebRtcEndpoint();
//                WebRtcEndpoint newPeer = newParticipant.getWebRtcEndpoint();
//                existPeer.connect(newPeer);
//                newPeer.connect(existPeer);
            } catch (final IOException e) {
                log.debug("ROOM {}: participant {} could not be notified",
                        name, participant.getName(), e);
            }
            participantsList.add(participant.getName());
        }

        return participantsList;
    }

    private void removeParticipant(String name) throws IOException {
        participants.remove(name);

        log.debug("ROOM {}: notifying all users that {} is leaving the room",
                this.name, name);

        final List<String> unnotifiedParticipants = new ArrayList<>();
        final JsonObject participantLeftJson = new JsonObject();
        participantLeftJson.addProperty("id", "participantLeft");
        participantLeftJson.addProperty("name", name);
        for (final UserSession participant : participants.values()) {
            try {
                participant.sendMessage(participantLeftJson);
            } catch (final IOException e) {
                unnotifiedParticipants.add(participant.getName());
            }
        }

        if (!unnotifiedParticipants.isEmpty()) {
            log.debug(
                    "ROOM {}: The users {} could not be notified that {} left the room",
                    this.name, unnotifiedParticipants, name);
        }

    }

    public void sendParticipantNames(UserSession user) throws IOException {

        final JsonArray participantsArray = new JsonArray();
        for (final UserSession participant : this.getParticipants()) {
            if (!participant.equals(user)) {
                final JsonElement participantName = new JsonPrimitive(
                        participant.getName());
                participantsArray.add(participantName);
            }
        }

        final JsonObject existingParticipantsMsg = new JsonObject();
        existingParticipantsMsg.addProperty("id", "existingParticipants");
        existingParticipantsMsg.add("data", participantsArray);
        log.debug("PARTICIPANT {}: sending a list of {} participants",
                user.getName(), participantsArray.size());
        user.sendMessage(existingParticipantsMsg);
    }

    /**
     * @return a collection with all the participants in the room
     */
    public Collection<UserSession> getParticipants() {
        return participants.values();
    }

    /**
     * @param name
     * @return the participant from this session
     */
    public UserSession getParticipant(String name) {
        return participants.get(name);
    }

    @Override
    public void close() {
        recorderEndpoint.stop();
        recorderEndpoint.release();

        for (final UserSession user : participants.values()) {
            try {
                user.close();
            } catch (IOException e) {
                log.debug("ROOM {}: Could not invoke close on participant {}",
                        this.name, user.getName(), e);
            }
        }

        participants.clear();

        pipeline.release(new Continuation<Void>() {

            @Override
            public void onSuccess(Void result) throws Exception {
                log.trace("ROOM {}: Released Pipeline", Room.this.name);
            }

            @Override
            public void onError(Throwable cause) throws Exception {
                log.warn("PARTICIPANT {}: Could not release Pipeline",
                        Room.this.name);
            }
        });

        log.debug("Room {} closed", this.name);
    }

}
