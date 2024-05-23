package org.commonhaus.automation.admin.api;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;

public class Message {

    static ObjectMapper mapper;

    static ObjectMapper getMapper() {
        ObjectMapper om = mapper;
        if (om == null) {
            om = mapper = Arc.container().instance(ObjectMapper.class).get();
        }
        return om;
    }

    enum Type {
        HAUS,
        EMAIL,
        ERROR,
        INFO
    }

    public static class MessageEncoder implements Encoder.Text<Message> {
        @Override
        public String encode(Message object) throws EncodeException {
            try {
                return getMapper().writeValueAsString(object);
            } catch (JsonProcessingException e) {
                throw new EncodeException(object, e.toString());
            }
        }
    }

    public static class MessageDecoder implements Decoder.Text<Message> {
        @Override
        public Message decode(String s) throws DecodeException {
            try {
                return getMapper().readValue(s, Message.class);
            } catch (JsonProcessingException e) {
                throw new DecodeException(s, e.toString());
            }
        }

        @Override
        public boolean willDecode(String msg) {
            return true;
        }
    }

    Type type;
    Object payload;

    public Message(Type type, Object payload) {
        this.type = type;
        this.payload = payload;
    }
}
