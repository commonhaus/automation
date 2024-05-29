package org.commonhaus.automation.admin.api;

import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;

public class MemberApiResponse {

    static ObjectMapper mapper;

    static ObjectMapper getMapper() {
        ObjectMapper om = mapper;
        if (om == null) {
            om = mapper = Arc.container().instance(ObjectMapper.class).get();
        }
        return om;
    }

    enum Type {
        ALIAS,
        EMAIL,
        ERROR,
        HAUS,
        INFO
    }

    // attributes discovered at any visbility
    // ignore this attribute. See AnyGetter/AnySetter
    @JsonIgnore
    private Map<Type, Object> data = new HashMap<>();

    @JsonAnyGetter
    public Map<Type, Object> getData() {
        return data;
    }

    @JsonAnySetter
    public void setData(Type key, Object value) {
        data.put(key, value);
    }

    public MemberApiResponse() {
    }

    public MemberApiResponse(Type key, Object payload) {
        setData(key, payload);
    }

    public static class MessageEncoder implements Encoder.Text<MemberApiResponse> {
        @Override
        public String encode(MemberApiResponse object) throws EncodeException {
            try {
                return getMapper().writeValueAsString(object);
            } catch (JsonProcessingException e) {
                throw new EncodeException(object, e.toString());
            }
        }
    }

    public static class MessageDecoder implements Decoder.Text<MemberApiResponse> {
        @Override
        public MemberApiResponse decode(String s) throws DecodeException {
            try {
                return getMapper().readValue(s, MemberApiResponse.class);
            } catch (JsonProcessingException e) {
                throw new DecodeException(s, e.toString());
            }
        }

        @Override
        public boolean willDecode(String msg) {
            return true;
        }
    }

    public void addAll(MemberApiResponse data2) {
        for (Map.Entry<Type, Object> entry : data2.getData().entrySet()) {
            data.put(entry.getKey(), entry.getValue());
        }
    }
}
