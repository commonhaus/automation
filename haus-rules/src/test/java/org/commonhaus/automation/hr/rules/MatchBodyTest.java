package org.commonhaus.automation.hr.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.hr.EventQueryContext;
import org.junit.jupiter.api.Test;

class MatchBodyTest {

    private EventQueryContext mockQc(String body) {
        EventQueryContext qc = mock(EventQueryContext.class);
        EventData eventData = mock(EventData.class);
        when(qc.getEventData()).thenReturn(eventData);
        when(eventData.getBody()).thenReturn(body);
        return qc;
    }

    @Test
    void substringPatternMatchesWhenBodyContainsIt() {
        MatchBody rule = new MatchBody("bylaws");
        assertThat(rule.matches(mockQc("This proposal amends the bylaws of the foundation."))).isTrue();
    }

    @Test
    void substringPatternDoesNotMatchWhenBodyLacksIt() {
        MatchBody rule = new MatchBody("bylaws");
        assertThat(rule.matches(mockQc("This proposal amends the charter."))).isFalse();
    }

    @Test
    void patternCaseInsensitive() {
        MatchBody rule = new MatchBody("bylaws");
        assertThat(rule.matches(mockQc("BYLAWS update"))).isTrue();
    }

    @Test
    void regexAnchoredDoesNotMatchAnIndividualLine() {
        MatchBody rule = new MatchBody("^bylaws$");
        assertThat(rule.matches(mockQc("bylaws"))).isTrue();
        assertThat(rule.matches(mockQc("amends the bylaws"))).isFalse();
        assertThat(rule.matches(mockQc("amends the foundation\nbylaws\nof the charter"))).isFalse();
    }

    @Test
    void emptyBodyReturnsFalse() {
        MatchBody rule = new MatchBody("bylaws");
        assertThat(rule.matches(mockQc(""))).isFalse();
    }

    @Test
    void nullBodyReturnsFalse() {
        MatchBody rule = new MatchBody("bylaws");
        assertThat(rule.matches(mockQc(null))).isFalse();
    }

    @Test
    void nullEventDataReturnsFalse() {
        MatchBody rule = new MatchBody("bylaws");
        EventQueryContext qc = mock(EventQueryContext.class);
        when(qc.getEventData()).thenReturn(null);
        assertThat(rule.matches(qc)).isFalse();
    }
}
