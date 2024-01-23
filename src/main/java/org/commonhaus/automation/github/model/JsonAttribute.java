package org.commonhaus.automation.github.model;

import java.util.Date;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;

import org.commonhaus.automation.github.CFGHApp;

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
    action,
    activeLockReason,
    addDiscussionComment,
    answer,
    answerChosenAt,
    author,
    authorAssociation,
    avatarUrl("avatar_url"),
    body,
    category,
    changes,
    closed,
    closedAt,
    color,
    comment,
    confused,
    content,
    createdAt("created_at"),
    deletedAt,
    description,
    discussion,
    discussionCategories,
    discussion_id,
    discussions,
    editedAt,
    editor,
    emoji,
    endCursor,
    eyes,
    from,
    full_name,
    hasNextPage,
    heart,
    hooray,
    id,
    includesCreatedEdit,
    installation,
    isAnswer,
    isAnswered,
    isDefault("default"),
    label,
    labels,
    lastEditedAt,
    laugh,
    locked,
    login,
    minusOne,
    name,
    new_discussion,
    new_repository,
    node,
    node_id,
    nodes,
    number,
    old_answer,
    organization,
    owner,
    pageInfo,
    parent_id,
    plusOne,
    publishedAt,
    reactableId,
    reactions,
    replyTo,
    repository,
    rocket,
    sender,
    state,
    stateReason,
    title,
    total_count,
    updatedAt("updated_at"),
    upvoteCount,
    url("html_url"),
    user,
    viewer,
    viewerCanDelete,
    viewerCanMarkAsAnswer,
    viewerCanMinimize,
    viewerCanReact,
    viewerCanUnmarkAsAnswer,
    viewerCanUpdate,
    viewerCanUpvote,
    viewerCannotUpdateReasons,
    viewerDidAuthor,
    viewerHasUpvoted,
    ;

    private final String nodeName;
    private final boolean alternateName;

    private JsonAttribute() {
        this.nodeName = this.name();
        this.alternateName = false;
    }

    private JsonAttribute(String nodeName) {
        this.nodeName = nodeName;
        this.alternateName = true;
    }

    public String getNodeName() {
        return nodeName;
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
     * @return boolean with value from nodeName (or name()) attribute of object or defaultValue
     */
    public boolean booleanFrom(JsonObject object, boolean defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        return alternateName
                ? object.getBoolean(nodeName, object.getBoolean(name(), defaultValue))
                : object.getBoolean(nodeName, defaultValue);
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
     * @return String with value from nodeName (or name()) attribute of object or defaultValue
     */
    public String stringFrom(JsonObject object, String defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        return alternateName
                ? object.getString(nodeName, object.getString(name(), defaultValue))
                : object.getString(nodeName, defaultValue);
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
        return value == null || value.getValueType() == ValueType.NULL ? null : JsonNumber.class.cast(value).intValue();
    }

    /**
     * @return int with value from nodeName (or name()) attribute of object or default value
     */
    public int integerFrom(JsonObject object, int defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        return alternateName
                ? object.getInt(nodeName, object.getInt(name(), defaultValue))
                : object.getInt(nodeName, defaultValue);
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
        return CFGHApp.parseDate(timestamp);
    }

    /**
     * @return Actor constructed from nodeName (or name()) attribute of object
     */
    public Actor actorFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Actor(field);
    }

    /**
     * @return Discussion constructed from nodeName (or name()) attribute of object
     */
    public Discussion discussionFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Discussion(field);
    }

    /**
     * @return DiscussionCategory constructed from nodeName (or name()) attribute of object
     */
    public DiscussionCategory discussionCategoryFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DiscussionCategory(field);
    }

    /**
     * @return DiscussionComment constructed from nodeName (or name()) attribute of object
     */
    public DiscussionComment discussionCommentFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DiscussionComment(field);
    }

    /**
     * @return Label constructed from nodeName (or name()) attribute of object
     */
    public Label labelFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Label(field);
    }

    /** @return JsonObject with nodeName (or name()) from object */
    public JsonObject jsonObjectFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
        return value == null || value.getValueType() == ValueType.NULL ? null : JsonObject.class.cast(value);
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
        return value == null || value.getValueType() == ValueType.NULL ? null : JsonArray.class.cast(value);
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

    public String stringifyNodeFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = alternateName
                ? object.getOrDefault(nodeName, object.get(name()))
                : object.get(nodeName);
        return value == null || value.getValueType() == ValueType.NULL ? null : value.toString();
    }
}