package org.commonhaus.automation.github.hr.rules;

import java.util.regex.Pattern;

import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.hr.EventQueryContext;

public class MatchBody {
    public final Pattern matchPattern;

    public MatchBody(String pattern) {
        this.matchPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    }

    public boolean matches(EventQueryContext qc) {
        EventData eventData = qc.getEventData();
        if (eventData == null) {
            return false;
        }

        String body = eventData.getBody();
        return body != null && matchPattern.matcher(body).matches();
    }
}
