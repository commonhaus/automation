package org.commonhaus.automation.hr.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.hr.config.NoticeConfig;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

class ActionDeserializerTest {

    private final ObjectMapper mapper = ContextService.yamlMapper;

    @Test
    void deserializesLabelActionFromArray() throws Exception {
        String yaml = """
                actions:
                  addLabel:
                    - label1
                    - label2
                """;
        NoticeConfig config = mapper.readValue(yaml, NoticeConfig.class);
        assertThat(config.actions).containsKey("addLabel");
        assertThat(config.actions.get("addLabel")).isInstanceOf(LabelAction.class);
    }

    @Test
    void deserializesEmailActionFromObjectWithAddress() throws Exception {
        String yaml = """
                actions:
                  sendEmail:
                    address: team@example.com
                """;
        NoticeConfig config = mapper.readValue(yaml, NoticeConfig.class);
        assertThat(config.actions).containsKey("sendEmail");
        assertThat(config.actions.get("sendEmail")).isInstanceOf(EmailAction.class);
    }

    @Test
    void throwsExceptionOnUnrecognizedActionShape() {
        String yaml = """
                actions:
                  invalidAction:
                    unknownField: someValue
                """;
        assertThatThrownBy(() -> mapper.readValue(yaml, NoticeConfig.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("Unrecognized action shape");
    }

    @Test
    void throwsExceptionOnScalarActionShape() {
        String yaml = """
                actions:
                  invalidAction: "just a string"
                """;
        assertThatThrownBy(() -> mapper.readValue(yaml, NoticeConfig.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("Unrecognized action shape");
    }
}
