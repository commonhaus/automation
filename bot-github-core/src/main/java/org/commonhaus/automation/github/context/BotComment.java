package org.commonhaus.automation.github.context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotComment {
    private final String itemId;
    private final String id;
    private final int databaseId;
    private final String url;
    private String bodyString;

    BotComment(String itemId, DataCommonComment comment) {
        this.bodyString = comment.body;
        this.databaseId = comment.databaseId;
        this.id = comment.id;
        this.itemId = itemId;
        this.url = comment.url;
    }

    // For dry run: this comment exists (in test repo), but probably not on the
    // original item
    BotComment(String itemId) {
        this.bodyString = "";
        this.databaseId = 8164728;
        this.id = "DC_kwDOLDuJqs4AfJV4";
        this.itemId = itemId;
        this.url = "https://github.com/commonhaus/automation-test/discussions/6#discussioncomment-8164728";
    }

    public String getItemId() {
        return itemId;
    }

    public String getCommentId() {
        return id;
    }

    public int getCommentDatabaseId() {
        return databaseId;
    }

    public String getUrl() {
        return url;
    }

    public String getBody() {
        return bodyString;
    }

    public BotComment setBody(String bodyString) {
        this.bodyString = bodyString;
        return this;
    }

    public boolean requiresUpdate(String bodyString) {
        return !this.bodyString.equals(bodyString);
    }

    public String markdownLink(String linkText) {
        return String.format("[%s](%s \"%s\")", linkText, url, id);
    }

    public static String getCommentId(Pattern botCommentPattern, String itemBody) {
        Matcher matcher = botCommentPattern.matcher(itemBody);
        if (matcher.find()) {
            if (matcher.group(2) != null) {
                return matcher.group(2);
            }
            int lastDash = matcher.group(1).lastIndexOf("-");
            return matcher.group(1).substring(lastDash + 1);
        }
        return null;
    }
}
