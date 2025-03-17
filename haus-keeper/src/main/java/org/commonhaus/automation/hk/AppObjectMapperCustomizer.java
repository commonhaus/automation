package org.commonhaus.automation.hk;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Unremovable;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.quarkus.logging.Log;

@Singleton
@Unremovable
class AppObjectMapperCustomizer implements ObjectMapperCustomizer {
    public void customize(ObjectMapper mapper) {
        Log.debug("Customizing ObjectMapper");
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(Include.NON_EMPTY)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.NON_PRIVATE);
    }
}
