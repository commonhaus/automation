package org.commonhaus.automation.github.rules;

import java.util.regex.Pattern;

import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.context.EventData;

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

        String pattern = "^(?!.*(?:No votes \\(non-bot reactions\\) found on this item\\.|vote::result will close the vote)).*$";
        Pattern mp = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);

        String qc2 = "Some text before vote::result will close the vote and some text after";
        String qc1 = "Some text before No votes (non-bot reactions) found on this item. and some text after";
        String qc3 = "This string does not contain the forbidden phrases";

        System.out.println("Test 1: " + mp.matcher(qc1).matches()); // Should print: false
        System.out.println("Test 2: " + mp.matcher(qc2).matches()); // Should print: false
        System.out.println("Test 3: " + mp.matcher(qc3).matches()); // Should print: true

        String body = eventData.getBody();
        System.out.println("Test 4: " + mp.matcher(body).matches());
        System.out.println("Test 5: " + matchPattern.matcher(body).matches());
        return body != null && matchPattern.matcher(body).matches();
    }
}
