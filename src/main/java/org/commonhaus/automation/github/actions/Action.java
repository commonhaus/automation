package org.commonhaus.automation.github.actions;

import java.io.IOException;
import java.util.List;

import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.actions.Action.ActionDeserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

@JsonDeserialize(using = ActionDeserializer.class)
public abstract class Action {
    final static TypeReference<List<String>> LIST_STRING = new TypeReference<>() {
    };

    public abstract void apply(QueryContext queryContext);

    public static class ActionDeserializer extends StdDeserializer<Action> {
        public ActionDeserializer() {
            this(null);
        }

        public ActionDeserializer(Class<Action> vc) {
            super(vc);
        }

        @Override
        public Action deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            ObjectMapper mapper = (ObjectMapper) jp.getCodec();
            JsonNode root = mapper.readTree(jp);
            if (root.isArray()) {
                LabelAction labelsAction = new LabelAction();
                labelsAction.labels = mapper.convertValue(root, LIST_STRING);
                return labelsAction;
            } else if (root.has("address")) {
                return mapper.convertValue(root, EmailAction.class);
            } else if (root.has("repo")) {
                return mapper.convertValue(root, DispatchAction.class);
            }
            return null;
        }
    }
}