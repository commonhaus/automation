package org.commonhaus.automation.github;

import java.util.Date;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * List of Json fields returned from GraphQL queries.
 * Reader wraps construction of base field types from the Json object. 
 * 
 * Why? This avoids finger checks from a lot of hard-coded strings
 * for fields. It also ensures we're using sane defaults, and converting
 * from Json in a consistent way for more complex types
 */
public enum JsonAttribute {
    activeLockReason, 
    answerChosenAt, 
    author, 
    authorAssociation, 
    avatarUrl, 
    avatar_url, 
    body,
    category, 
    closed, 
    closedAt, 
    confused, 
    content, 
    createdAt,
    created_at, 
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
    hasNextPage, 
    heart, 
    hooray, 
    html_url, 
    id,
    includesCreatedEdit, 
    isAnswered, 
    lastEditedAt, 
    laugh, 
    locked,
    login, 
    minusOne, 
    name, 
    node, 
    node_id, 
    nodes,
    number, 
    pageInfo, 
    parent_id, 
    plusOne, 
    publishedAt, 
    reactableId, 
    reactions, 
    repository,
    rocket, 
    state, 
    stateReason, 
    title,
    total_count, 
    updatedAt, 
    updated_at, 
    upvoteCount, 
    url, 
    user, from, 
    ;

    private final String nodeName;
    private JsonAttribute() {
        this.nodeName = this.name();
    }
    private JsonAttribute(String nodeName) {
        this.nodeName = nodeName;
    }
    public String getNodeName() {
        return nodeName;
    }
    public boolean booleanFromOrFalse(JsonObject object) {
        if (object == null) {
            return false;
        }
        return object.getBoolean(nodeName, false);
    }
    public boolean booleanFrom(JsonObject object, boolean defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        return object.getBoolean(nodeName, defaultValue);
    }
    public String stringFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return object.getString(nodeName);
    }
    public String stringFrom(JsonObject object, String defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        return object.getString(nodeName, defaultValue);
    }
    public Integer integerFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = object.get(nodeName);
        return value == null ? null : JsonNumber.class.cast(value).intValue();
    }
    public int integerFrom(JsonObject object, int defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        return object.getInt(nodeName, defaultValue);
    }
    public Date dateFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        String timestamp = object.getString(nodeName, null);
        return CFGHApp.parseDate(timestamp);
    }
    public Actor actorFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Actor(jsonObjectFrom(object));
    }
    public Discussion discussionFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Discussion(jsonObjectFrom(object));
    }
    public DiscussionCategory discussionCategoryFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DiscussionCategory(jsonObjectFrom(object));
    }
    public JsonObject jsonObjectFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return (JsonObject) object.get(nodeName);
    }
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
    public JsonArray jsonArrayFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return (JsonArray) object.get(nodeName);
    }
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
}
