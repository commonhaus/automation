package org.commonhaus.automation.github.model;

import jakarta.json.JsonObject;

public class DataCommonComment extends DataCommonObject {

    static final String COMMENT_FIELDS = COMMON_OBJECT_FIELDS + """
            body
            includesCreatedEdit
            viewerDidAuthor
                """;

    public final boolean includesCreatedEdit;

    public final boolean viewerDidAuthor;

    public DataCommonComment(JsonObject object) {
        super(object);

        this.includesCreatedEdit = JsonAttribute.includesCreatedEdit.booleanFromOrFalse(object);
        this.viewerDidAuthor = JsonAttribute.viewerDidAuthor.booleanFromOrFalse(object);
    }
}
