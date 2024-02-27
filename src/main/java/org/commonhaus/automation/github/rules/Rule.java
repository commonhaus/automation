package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
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
    MatchCategory category;
    MatchPaths paths;
    MatchLabel label;

    public List<String> then;

    public boolean matches(QueryContext queryContext) {
        boolean matches = true;
        if (action != null) {
            matches &= action.matches(queryContext);
        }
        if (matches && category != null) {
            matches &= category.matches(queryContext);
        }
        if (matches && paths != null) {
            matches &= paths.matches(queryContext);
        }
        if (matches && label != null) {
            matches &= label.matches(queryContext);
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
                throws java.io.IOException, com.fasterxml.jackson.core.JsonProcessingException {
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            JsonNode node = mapper.readTree(p);
            Rule rule = new Rule();
            if (RuleType.action.existsIn(node)) {
                rule.action = new MatchAction(mapper.convertValue(RuleType.action.getFrom(node), LIST_STRING));
            }
            if (RuleType.category.existsIn(node)) {
                rule.category = new MatchCategory();
                rule.category.category = mapper.convertValue(RuleType.category.getFrom(node), LIST_STRING);
            }
            if (RuleType.label.existsIn(node)) {
                rule.label = new MatchLabel(mapper.convertValue(RuleType.label.getFrom(node), LIST_STRING));
            }
            if (RuleType.paths.existsIn(node)) {
                rule.paths = new MatchPaths();
                rule.paths.paths = mapper.convertValue(RuleType.paths.getFrom(node), LIST_STRING);
            }
            if (RuleType.then.existsIn(node)) {
                rule.then = mapper.convertValue(RuleType.then.getFrom(node), LIST_STRING);
            }
            return rule;
        }
    }

    enum RuleType {
        action,
        category,
        label,
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
