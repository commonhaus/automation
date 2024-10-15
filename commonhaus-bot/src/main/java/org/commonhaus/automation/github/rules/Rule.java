package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.rules.Rule.RuleDeserializer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

@JsonDeserialize(using = RuleDeserializer.class)
public class Rule {
    final static TypeReference<List<String>> LIST_STRING = new TypeReference<>() {
    };

    MatchAction action;
    MatchBody body;
    MatchCategory category;
    MatchPaths paths;
    MatchLabel label;
    MatchChangedLabel changedLabel;

    public List<String> then;

    public boolean matches(EventQueryContext qc) {
        boolean matches = true;
        if (action != null) {
            matches = action.matches(qc);
        }
        if (matches && body != null) {
            matches = body.matches(qc);
        }
        if (matches && category != null) {
            matches = category.matches(qc);
        }
        if (matches && paths != null) {
            matches = paths.matches(qc);
        }
        if (matches && label != null) {
            matches = label.matches(qc, qc.getNodeId());
        }
        if (matches && changedLabel != null) {
            matches = changedLabel.matches(qc);
        }
        return matches;
    }

    public static class RuleDeserializer extends StdDeserializer<Rule> {
        public RuleDeserializer() {
            this(null);
        }

        public RuleDeserializer(Class<Rule> vc) {
            super(vc);
        }

        @Override
        public Rule deserialize(com.fasterxml.jackson.core.JsonParser p,
                com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            JsonNode node = mapper.readTree(p);
            Rule rule = new Rule();

            JsonNode child = RuleType.action.getFrom(node);
            if (child != null) {
                rule.action = new MatchAction(mapper.convertValue(child, LIST_STRING));
            }

            child = RuleType.body.getFrom(node);
            if (child != null) {
                rule.body = new MatchBody(child.asText());
            }

            child = RuleType.category.getFrom(node);
            if (child != null) {
                rule.category = new MatchCategory(mapper.convertValue(child, LIST_STRING));
            }

            child = RuleType.label.getFrom(node);
            if (child != null) {
                rule.label = new MatchLabel(mapper.convertValue(child, LIST_STRING));
            }

            child = RuleType.label_change.getFrom(node);
            if (child != null) {
                rule.changedLabel = new MatchChangedLabel(mapper.convertValue(child, LIST_STRING));
            }

            child = RuleType.paths.getFrom(node);
            if (child != null) {
                rule.paths = new MatchPaths(mapper.convertValue(child, LIST_STRING));
            }

            child = RuleType.then.getFrom(node);
            if (child != null) {
                rule.then = mapper.convertValue(child, LIST_STRING);
            }
            return rule;
        }
    }

    enum RuleType {
        action,
        body,
        category,
        label,
        label_change,
        paths,
        then;

        boolean existsIn(JsonNode source) {
            if (source == null) {
                return false;
            }
            return source.has(this.name());
        }

        JsonNode getFrom(JsonNode source) {
            if (source == null) {
                return null;
            }
            return source.get(this.name());
        }
    }
}
