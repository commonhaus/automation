package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

public class CommonComment extends CommonObject {

    static final String COMMENT_FIELDS = COMMON_OBJECT_FIELDS + """
            body
            includesCreatedEdit
            viewerDidAuthor
                """;

    public final boolean includesCreatedEdit;

    public final boolean viewerDidAuthor;

    public CommonComment(JsonObject object) {
        super(object);

        this.includesCreatedEdit = JsonAttribute.includesCreatedEdit.booleanFromOrFalse(object);
        this.viewerDidAuthor = JsonAttribute.viewerDidAuthor.booleanFromOrFalse(object);
    }
}
