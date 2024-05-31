package org.commonhaus.automation.admin;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import io.quarkus.arc.Unremovable;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.quarkus.logging.Log;

@Singleton
@Unremovable
class AppObjectMapperCustomizer implements ObjectMapperCustomizer {
    public void customize(ObjectMapper mapper) {
        Log.debug("Customizing ObjectMapper");
        mapper.enable(Feature.IGNORE_UNKNOWN)
                .setSerializationInclusion(Include.NON_EMPTY)
                .setVisibility(VisibilityChecker.Std.defaultInstance()
                        .with(JsonAutoDetect.Visibility.ANY));
    }
}
