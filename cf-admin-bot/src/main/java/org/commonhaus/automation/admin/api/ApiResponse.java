package org.commonhaus.automation.admin.api;

import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ApiResponse {

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
        APPLY,
        EMAIL,
        ERROR,
        HAUS,
        INFO
    }

    // attributes discovered at any visbility
    // ignore this attribute. See AnyGetter/AnySetter
    @JsonIgnore
    private Map<Type, Object> data = new HashMap<>();

    @JsonIgnore
    Response.Status status = Response.Status.OK;

    public ApiResponse() {
    }

    public ApiResponse(Type key, Object payload) {
        setData(key, payload);
    }

    @JsonAnyGetter
    public Map<Type, Object> getData() {
        return data;
    }

    @JsonAnySetter
    public ApiResponse setData(Type key, Object value) {
        data.put(key, value);
        return this;
    }

    public ApiResponse addAll(ApiResponse data2) {
        for (Map.Entry<Type, Object> entry : data2.getData().entrySet()) {
            data.put(entry.getKey(), entry.getValue());
        }
        return this;
    }

    public <T> T value(Type key, Class<T> clazz) {
        return clazz.cast(data.get(key));
    }

    public Response.Status status() {
        return status;
    }

    public ApiResponse responseStatus(Response.Status status) {
        this.status = status;
        return this;
    }

    public Response finish() {
        return this.status() == Response.Status.OK
                ? Response.ok(this).build()
                : Response.status(this.status())
                        .entity(this)
                        .build();
    }

    public static class MessageEncoder implements Encoder.Text<ApiResponse> {
        @Override
        public String encode(ApiResponse object) throws EncodeException {
            try {
                return getMapper().writeValueAsString(object);
            } catch (JsonProcessingException e) {
                throw new EncodeException(object, e.toString());
            }
        }
    }

    public static class MessageDecoder implements Decoder.Text<ApiResponse> {
        @Override
        public ApiResponse decode(String s) throws DecodeException {
            try {
                return getMapper().readValue(s, ApiResponse.class);
            } catch (JsonProcessingException e) {
                throw new DecodeException(s, e.toString());
            }
        }

        @Override
        public boolean willDecode(String msg) {
            return true;
        }
    }
}
