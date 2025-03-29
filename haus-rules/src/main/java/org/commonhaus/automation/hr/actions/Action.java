package org.commonhaus.automation.hr.actions;

import java.io.IOException;
import java.util.List;

import org.commonhaus.automation.hr.EventQueryContext;
import org.commonhaus.automation.hr.actions.Action.ActionDeserializer;

import com.fasterxml.jackson.core.JsonParser;
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

    public abstract void apply(EventQueryContext qc);

    public static class ActionDeserializer extends StdDeserializer<Action> {
        public ActionDeserializer() {
            this(null);
        }

        public ActionDeserializer(Class<Action> vc) {
            super(vc);
        }

        @Override
        public Action deserialize(JsonParser jp, DeserializationContext context) throws IOException {
            ObjectMapper mapper = (ObjectMapper) jp.getCodec();
            JsonNode root = mapper.readTree(jp);
            if (root.isArray()) {
                return new LabelAction(mapper.convertValue(root, LIST_STRING));
            } else if (root.has("address")) {
                return new EmailAction(root.get("address"));
            }
            return null;
        }
    }
}
