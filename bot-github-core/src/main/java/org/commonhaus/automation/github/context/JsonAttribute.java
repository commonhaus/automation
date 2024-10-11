package org.commonhaus.automation.github.context;

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
import jakarta.json.JsonString;
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
    bio,
    body,
    category,
    changes,
    closed,
    closedAt("closed_at"),
    comment,
    commentEdge,
    comments,
    company,
    content,
    createIssue,
    createLabel,
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
    isActive,
    isCustomAmount("is_custom_amount"),
    isOneTime("is_one_time"),
    issue,
    issueComment,
    label,
    labelable,
    labels,
    lastEditedAt,
    latestReviews,
    login,
    monthlyPriceInCents("monthly_price_in_cents"),
    monthlyPriceInDollars("monthly_price_in_dollars"),
    name,
    node,
    node_id,
    nodes,
    number,
    organization,
    pageInfo,
    pullRequest("pull_request"),
    reactableId,
    reactions,
    removeLabelsFromLabelable,
    repositories,
    repositoriesAdded("repositories_added"),
    repositoriesRemoved("repositories_removed"),
    repository,
    reviewDecision,
    search,
    sponsorEntity("sponsor"),
    sponsorable,
    sponsorshipsAsMaintainer,
    state,
    submittedAt("submitted_at"),
    tier,
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

    public boolean existsIn(JsonObject object) {
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
     * @return Long with value from nodeName (or name()) attribute of object or null
     */
    public Long longFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
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

    public DataCommonComment commonCommentFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataCommonComment(field);
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
        JsonValue field = valueFrom(object);
        if (field == null) {
            return null;
        }

        JsonArray list;
        if (field.getValueType() == ValueType.OBJECT) {
            // "labels": {
            //     "nodes": [
            //         {
            //         "id": "LA_kwDOL8tG0s8AAAABpSSN4Q",
            //         "name": "application/accepted"
            //         }
            //     ],
            //     "pageInfo": {
            //         "hasNextPage": false,
            //         "endCursor": "MQ"
            //     }
            // }
            JsonObject o = field.asJsonObject();
            if (JsonAttribute.id.existsIn(o)) {
                return List.of(new DataLabel(o));
            }
            list = JsonAttribute.nodes.jsonArrayFrom(o);
        } else {
            // "labels": [
            //     {
            //       "id": 6605129827,
            //       "node_id": "LA_kwDOLDuJqs8AAAABibJIYw",
            //       "url": "https://api.github.com/repos/commonhaus-test/automation-test/labels/vote/open",
            //       "name": "vote/open",
            //       "color": "5319e7",
            //       "default": false,
            //       "description": ""
            //     }
            //   ],
            list = field.asJsonArray();
        }
        return list.stream()
                .map(JsonObject.class::cast)
                .map(DataLabel::new)
                .toList();
    }

    public DataTier tierFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataTier(field);
    }

    /** Bridge to GH* type usually parsed using Jackson; may be incomplete */
    public GHRepository repositoryFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        String value = field.toString();
        return value == null
                ? null
                : tryOrNull(value, GHRepository.class);
    }

    public List<GHRepository> repositoriesFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonArray list = jsonArrayFrom(object);
        return list == null
                ? null
                : list.stream()
                        .map(JsonObject.class::cast)
                        .map(x -> {
                            String fullName = x.getString("full_name");
                            String owner = fullName.substring(0, fullName.indexOf('/'));
                            return Json.createObjectBuilder(x)
                                    .add("owner", Json.createObjectBuilder()
                                            .add("name", owner)
                                            .build())
                                    .build();
                        })
                        .map(x -> tryOrNull(x.toString(), GHRepository.class))
                        .toList();
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
        JsonValue value = valueFrom(object);
        return value == null || value.getValueType() == ValueType.NULL ? null : (JsonObject) value;
    }

    private JsonValue valueFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
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
