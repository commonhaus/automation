package org.commonhaus.automation;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;

import org.commonhaus.automation.github.context.JsonAttribute;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectReader;

import io.quarkus.logging.Log;

public interface JsonAttributeAccessor {
    /** Bridge between JSON-B parsed types and Jackson-created GH* types */
    ObjectReader ghApiReader = GitHub.getMappingObjectReader();
    DateTimeFormatter DATE_TIME_PARSER_SLASHES = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm:ss Z");

    String alternateName();

    String name();

    boolean hasAlternateName();

    default boolean existsIn(JsonObject object) {
        if (object == null) {
            return false;
        }
        return hasAlternateName()
                ? object.containsKey(alternateName()) || object.containsKey(name())
                : object.containsKey(alternateName());
    }

    /**
     * @return boolean with value from alternateName() (or name()) attribute of object or false
     */
    default boolean booleanFromOrFalse(JsonObject object) {
        if (object == null) {
            return false;
        }
        return hasAlternateName()
                ? object.getBoolean(alternateName(), object.getBoolean(name(), false))
                : object.getBoolean(alternateName(), false);
    }

    /**
     * @return boolean with value from alternateName() (or name()) attribute of object or false
     */
    default boolean booleanFromOrDefault(JsonObject object, boolean defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        return hasAlternateName()
                ? object.getBoolean(alternateName(), object.getBoolean(name(), defaultValue))
                : object.getBoolean(alternateName(), defaultValue);
    }

    /**
     * @return String with value from alternateName() (or name()) attribute of object or null
     */
    default String stringFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return hasAlternateName()
                ? object.getString(alternateName(), object.getString(name(), null))
                : object.getString(alternateName(), null);
    }

    /**
     * @return Integer with value from alternateName() (or name()) attribute of object or null
     */
    default Integer integerFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = hasAlternateName()
                ? object.getOrDefault(alternateName(), object.get(name()))
                : object.get(alternateName());
        if (value == null || value.getValueType() == ValueType.NULL) {
            return null;
        }
        if (value.getValueType() == ValueType.STRING) {
            String stringValue = ((JsonString) value).getString();
            return Integer.valueOf(stringValue);
        }
        return ((JsonNumber) value).intValue();
    }

    /**
     * @return Long with value from alternateName() (or name()) attribute of object or null
     */
    default Long longFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = hasAlternateName()
                ? object.getOrDefault(alternateName(), object.get(name()))
                : object.get(alternateName());
        if (value == null || value.getValueType() == ValueType.NULL) {
            return null;
        }
        if (value.getValueType() == ValueType.STRING) {
            String stringValue = ((JsonString) value).getString();
            return Long.valueOf(stringValue);
        }
        return ((JsonNumber) value).longValue();
    }

    /**
     * @return Date constructed from alternateName() (or name()) attribute of object
     */
    default Instant instantFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        String timestamp = hasAlternateName()
                ? object.getString(alternateName(), object.getString(name(), null))
                : object.getString(alternateName(), null);
        return parseInstant(timestamp);
    }

    /** @return JsonObject with alternateName() (or name()) from object */
    default JsonObject jsonObjectFrom(JsonObject object) {
        JsonValue value = valueFrom(object);
        return value == null || value.getValueType() == ValueType.NULL ? null : (JsonObject) value;
    }

    default JsonValue valueFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return hasAlternateName()
                ? object.getOrDefault(alternateName(), object.get(name()))
                : object.get(alternateName());
    }

    /**
     * @return JsonObject from alternateName() (or name()) attribute
     *         after extracting intermediate nodes (using attributes) from
     *         original object
     */
    default JsonObject extractObjectFrom(JsonObject object, JsonAttribute... readers) {
        if (object == null) {
            return null;
        }
        for (JsonAttribute reader : readers) {
            object = reader.jsonObjectFrom(object);
            if (object == null) {
                return null;
            }
        }
        return this.jsonObjectFrom(object);
    }

    /** @return JsonArray with alternateName() (or name()) from object */
    default JsonArray jsonArrayFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = hasAlternateName()
                ? object.getOrDefault(alternateName(), object.get(name()))
                : object.get(alternateName());
        return value == null || value.getValueType() == ValueType.NULL ? null : (JsonArray) value;
    }

    /**
     * @return JsonArray constructed from alternateName() (or name()) attribute
     *         after extracting intermediate nodes (using attributes) from
     *         original object
     */
    default JsonArray extractArrayFrom(JsonObject object, JsonAttribute... readers) {
        if (object == null) {
            return null;
        }
        for (JsonAttribute reader : readers) {
            object = reader.jsonObjectFrom(object);
            if (object == null) {
                return null;
            }
        }
        return this.jsonArrayFrom(object);
    }

    default <T> T tryOrNull(String string, Class<T> clazz) {
        try {
            return ghApiReader.readValue(string, clazz);
        } catch (IOException e) {
            Log.debugf(e, "Unable to parse %s as %s", string, clazz);
            return null;
        }
    }

    /** Parses to Instant as GitHubClient.parseInstant does */
    static Instant parseInstant(String timestamp) {
        if (timestamp == null) {
            return null;
        }

        if (timestamp.charAt(4) == '/') {
            // Unsure where this is used, but retained for compatibility.
            return Instant.from(DATE_TIME_PARSER_SLASHES.parse(timestamp));
        } else {
            return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp));
        }
    }

    static JsonObject unpack(String payload) {
        JsonReader reader = Json.createReader(new java.io.StringReader(payload));
        return reader.readObject();
    }
}
