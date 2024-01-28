package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.QueryHelper.QueryContext;
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
    MatchFilePath path;
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
        if (matches && path != null) {
            matches &= path.matches(queryContext);
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
            if (node.has("action")) {
                rule.action = new MatchAction();
                rule.action.actions = mapper.convertValue(node.get("action"), LIST_STRING);
            }
            if (node.has("category")) {
                rule.category = new MatchCategory();
                rule.category.category = mapper.convertValue(node.get("category"), LIST_STRING);
            }
            if (node.has("label")) {
                rule.label = new MatchLabel();
                rule.label.labels = mapper.convertValue(node.get("label"), LIST_STRING);
            }
            if (node.has("path")) {
                rule.path = new MatchFilePath();
                rule.path.paths = mapper.convertValue(node.get("path"), LIST_STRING);
            }
            if (node.has("then")) {
                rule.then = mapper.convertValue(node.get("then"), LIST_STRING);
            }
            return rule;
        }
    }
}
