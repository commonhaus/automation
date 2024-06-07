package org.commonhaus.automation.admin.api;

import java.io.Closeable;
import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EncodeException;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import org.commonhaus.automation.admin.api.ApiResponse.MessageDecoder;
import org.commonhaus.automation.admin.api.ApiResponse.MessageEncoder;
import org.commonhaus.automation.admin.api.ApiResponse.Type;
import org.commonhaus.automation.admin.github.AppContextService;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Authenticated
@ServerEndpoint(value = "/member/{id}/stream", encoders = MessageEncoder.class, decoders = MessageDecoder.class)
public class MemberSocket {

    @Inject
    AppContextService ctx;

    @Inject
    MemberSession memberProfile;

    @OnOpen
    public void onOpen(Session session) {
        if (!memberProfile.hasConnection()) {
            sendMessage(session, new ApiResponse(Type.ERROR, "Problem working with GitHub credentials."));

            tryToClose(session,
                    new CloseReason(CloseCodes.UNEXPECTED_CONDITION, "No GitHub connection"));
        }
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        Log.debugf("[onMessage] (%s) %s %s", memberProfile.login(), message, memberProfile);
        try {

        } catch (Exception e) {
            Log.debugf("[onMessage] (%s) Uncaught exception handling message %s", memberProfile.login(), e);
        }
    }

    @OnClose
    public void onClose(Session session) {
        Log.debugf("[onClose] (%s)", memberProfile.login());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        Log.debugf("[onError] (%s) %s", memberProfile.login(), t);
        tryToClose(session,
                new CloseReason(CloseCodes.UNEXPECTED_CONDITION,
                        "Client error"));
    }

    public boolean sendMessage(Session session, ApiResponse message) {
        if (session.isOpen()) {
            try {
                session.getBasicRemote().sendObject(message);
                return true;
            } catch (EncodeException e) {
                // Something was wrong encoding this message, but the connection
                // is likely just fine.
                Log.errorf(e, "Unexpected condition writing message: %s", e);
            } catch (IOException ioe) {
                // An IOException, on the other hand, suggests the connection is
                // in a bad state.
                Log.errorf(ioe, "Unexpected condition writing message: %s", ioe);
                // tryToClose(session, new CloseReason(CloseCodes.UNEXPECTED_CONDITION,
                //         trimReason(ioe.toString())));
            }
        }
        return false;
    }

    public static String trimReason(String message) {
        return message.length() > 123 ? message.substring(0, 123) : message;
    }

    /**
     * Try to close the WebSocket session and give a reason for doing so.
     *
     * @param s Session to close
     * @param reason {@link CloseReason} the WebSocket is closing.
     */
    public void tryToClose(Session s, CloseReason reason) {
        if (s != null) {
            try {
                s.close(reason);
            } catch (IOException e) {
                tryToClose(s);
            }
        }
    }

    public static void tryToClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e1) {
            }
        }
    }
}
