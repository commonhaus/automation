package org.commonhaus.automation.admin.data;

import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ApiResponse {

    public enum Type {
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
        Log.debugf("ApiResponse.finish: %s", this.status());
        return this.status() == Response.Status.OK
                ? Response.ok(this).build()
                : Response.status(this.status())
                        .entity(this)
                        .build();
    }
}
