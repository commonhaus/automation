package org.commonhaus.automation.github.model;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;

import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectReader;

import io.quarkus.logging.Log;

/**
 * This enumeration defines and manages a set of known field names utilized in GitHub API responses.
 * It provides a structured approach to handle JSON data without relying on reflection, using
 * enum values to avoid errors associated with hard-coded strings and to provide common
 * POJO (Plain Old Java Object) construction mechanisms.
 * <p>
 * Usage Examples:
 * </p>
 * <ul>
 * <li>
 * Extracting a field using the enum:
 * <br>
 * {@code JsonAttribute.author.actorFrom(object)}
 * <br>
 * This extracts the {@code author} field from the given JsonObject to construct an Actor.
 * </li>
 * <li>
 * Using an alternate enum name:
 * <br>
 * {@code JsonAttribute.url.stringFrom(object)}
 * <br>
 * This tries to use the {@code html_url} attribute from the JsonObject. If {@code html_url} is not present,
 * it defaults to using the {@code url} attribute.
 * </li>
 * </ul>
 */
public enum JsonAttribute {
    addDiscussionComment,
    addLabelsToLabelable,
    answer,
    author,
    avatarUrl("avatar_url"),
    body,
    category,
    changes,
    closed,
    closedAt,
    comment,
    commentEdge,
    comments,
    content,
    createdAt("created_at"),
    databaseId,
    discussion,
    discussionCategories,
    discussion_id,
    endCursor,
    from,
    hasNextPage,
    id,
    installation,
    issue,
    issueComment,
    label,
    labelable,
    labels,
    login,
    name,
    node,
    node_id,
    nodes,
    number,
    organization,
    pageInfo,
    pullRequest,
    reactableId,
    reactions,
    removeLabelsFromLabelable,
    repository,
    search,
    title,
    updateDiscussionComment,
    updateIssue,
    updateIssueComment,
    updatePullRequest,
    updatedAt("updated_at"),
    url("html_url"),
    user,
    viewer,
    ;

    /** Bridge between JSON-B parsed types and Jackson-created GH* types */
    static final ObjectReader ghApiReader = GitHub.getMappingObjectReader();
    static final DateTimeFormatter DATE_TIME_PARSER_SLASHES = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm:ss Z");

    private final String nodeName;
    private final boolean alternateName;

    JsonAttribute() {
        this.nodeName = this.name();
        this.alternateName = false;
    }

    JsonAttribute(String nodeName) {
        this.nodeName = nodeName;
        this.alternateName = true;
    }

    boolean existsIn(JsonObject object) {
        if (object == null) {
            return false;
        }
        return alternateName
                ? object.containsKey(nodeName) || object.containsKey(name())
                : object.containsKey(nodeName);
    }

    /**
     * @return boolean with value from nodeName (or name()) attribute of object or false
     */
    public boolean booleanFromOrFalse(JsonObject object) {
        if (object == null) {
            return false;
        }
        return alternateName
                ? object.getBoolean(nodeName, object.getBoolean(name(), false))
                : object.getBoolean(nodeName, false);
    }

    /**
     * @return String with value from nodeName (or name()) attribute of object or null
     */
    public String stringFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return alternateName
                ? object.getString(nodeName, object.getString(name(), null))
                : object.getString(nodeName, null);
    }

    /**
     * @return Integer with value from nodeName (or name()) attribute of object or null
     */
    public Integer integerFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
        return value == null || value.getValueType() == ValueType.NULL ? null : ((JsonNumber) value).intValue();
    }

    /**
     * @return Long with value from nodeName (or name()) attribute of object or null
     */
    public Long longFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
        return value == null || value.getValueType() == ValueType.NULL ? null : ((JsonNumber) value).longValue();
    }

    /**
     * @return Date constructed from nodeName (or name()) attribute of object
     */
    public Date dateFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        String timestamp = alternateName
                ? object.getString(nodeName, object.getString(name(), null))
                : object.getString(nodeName, null);
        return parseDate(timestamp);
    }

    /**
     * @return Actor constructed from nodeName (or name()) attribute of object
     */
    public DataActor actorFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataActor(field);
    }

    /**
     * @return DataCommonItem constructed from nodeName (or name()) attribute of object
     */
    public DataCommonItem commonItemFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataCommonItem(field);
    }

    /**
     * @return Discussion constructed from nodeName (or name()) attribute of object
     */
    public DataDiscussion discussionFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataDiscussion(field);
    }

    /**
     * @return DiscussionCategory constructed from nodeName (or name()) attribute of object
     */
    public DataDiscussionCategory discussionCategoryFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataDiscussionCategory(field);
    }

    /**
     * @return DataDiscussionComment constructed from nodeName (or name()) attribute of object
     */
    public DataDiscussionComment discussionCommentFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataDiscussionComment(field);
    }

    /**
     * @return DataIssueComment constructed from nodeName (or name()) attribute of object
     */
    public DataIssueComment issueCommentFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataIssueComment(field);
    }

    /**
     * @return Label constructed from nodeName (or name()) attribute of object
     */
    public DataLabel labelFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataLabel(field);
    }

    public List<DataLabel> labelsFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        if (field == null) {
            return null;
        }

        JsonArray list;
        if (field.getValueType() == ValueType.OBJECT) {
            if (JsonAttribute.id.existsIn(field)) {
                return List.of(new DataLabel(field));
            }
            list = JsonAttribute.nodes.jsonArrayFrom(field);
        } else {
            list = jsonArrayFrom(field);
        }
        return list.stream()
                .map(JsonObject.class::cast)
                .map(DataLabel::new)
                .toList();
    }

    /** Bridge to GH* type usually parsed using Jackson; may be incomplete */
    public GHRepository repositoryFrom(JsonObject object) {
        String value = stringFrom(object);
        return value == null
                ? null
                : tryOrNull(value, GHRepository.class);
    }

    /** Bridge to GH* type usually parsed using Jackson; may be incomplete */
    public GHOrganization organizationFrom(JsonObject object) {
        String value = stringFrom(object);
        return value == null
                ? null
                : tryOrNull(value, GHOrganization.class);
    }

    /** Bridge to GH* type usually parsed using Jackson; may be incomplete */
    public GHAppInstallation appInstallationFrom(JsonObject object) {
        String value = stringFrom(object);
        return value == null
                ? null
                : tryOrNull(value, GHAppInstallation.class);
    }

    /** @return JsonObject with nodeName (or name()) from object */
    public JsonObject jsonObjectFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
        return value == null || value.getValueType() == ValueType.NULL ? null : (JsonObject) value;
    }

    /**
     * @return JsonObject from nodeName (or name()) attribute
     *         after extracting intermediate nodes (using attributes) from
     *         original object
     */
    public JsonObject extractObjectFrom(JsonObject object, JsonAttribute... readers) {
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

    /** @return JsonArray with nodeName (or name()) from object */
    public JsonArray jsonArrayFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
        return value == null || value.getValueType() == ValueType.NULL ? null : (JsonArray) value;
    }

    /**
     * @return JsonArray constructed from nodeName (or name()) attribute
     *         after extracting intermediate nodes (using attributes) from
     *         original object
     */
    public JsonArray extractArrayFrom(JsonObject object, JsonAttribute... readers) {
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

    private <T> T tryOrNull(String string, Class<T> clazz) {
        try {
            return ghApiReader.readValue(string, clazz);
        } catch (IOException e) {
            Log.debugf(e, "Unable to parse %s as %s", string, clazz);
            return null;
        }
    }

    /** Parses to Date as GitHubClient.parseDate does */
    public static Date parseDate(String timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Date.from(parseInstant(timestamp));
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

    public static JsonObject unpack(String payload) {
        JsonReader reader = Json.createReader(new java.io.StringReader(payload));
        return reader.readObject();
    }
}
