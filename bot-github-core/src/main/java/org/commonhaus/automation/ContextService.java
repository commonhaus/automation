package org.commonhaus.automation;

import java.io.IOException;
import java.util.function.Supplier;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.mail.LogMailer;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GitHub;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public interface ContextService {

    /**
     * A YAML mapper that is configured to be pretty-printed and to use plain scalars.
     * <p>
     * Supplier creates the singleton instance when the interface is loaded.
     */
    public static ObjectMapper yamlMapper = new Supplier<ObjectMapper>() {
        @Override
        public ObjectMapper get() {
            DumperOptions options = new DumperOptions();
            options.setDefaultScalarStyle(ScalarStyle.PLAIN);
            options.setDefaultFlowStyle(FlowStyle.AUTO);
            options.setPrettyFlow(true);

            return new ObjectMapper(new YAMLFactoryBuilder(new YAMLFactory())
                    .dumperOptions(options).build())
                    .findAndRegisterModules()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .setSerializationInclusion(Include.NON_EMPTY)
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.NON_PRIVATE);
        }
    }.get();

    default ObjectMapper yamlMapper() {
        return yamlMapper;
    }

    default JsonNode parseYamlFile(GHContent content) throws IOException {
        return yamlMapper().readTree(content.read());
    }

    default <T> T parseYamlFile(GHContent content, Class<T> type) throws IOException {
        return yamlMapper().readValue(content.read(), type);
    }

    default <T> T parseYamlFile(GHContent content, TypeReference<T> type) throws IOException {
        return yamlMapper().readValue(content.read(), type);
    }

    default <T> String writeYamlValue(T user) throws IOException {
        return yamlMapper().writeValueAsString(user);
    }

    boolean isDryRun();

    boolean isDiscoveryEnabled();

    String[] botErrorEmailAddress();

    String[] getErrorAddresses(EmailNotification notifications);

    GitHub getInstallationClient(long installationId);

    DynamicGraphQLClient getInstallationGraphQLClient(long installationId);

    ScopedQueryContext getOrgScopedQueryContext(String orgName);

    /**
     * Log exception and send email
     *
     * @see LogMailer#sendEmail(String, String, String, String[])
     */
    void logAndSendEmail(String logId, String title, Throwable t, String[] addresses);

    default void logAndSendEmail(String logId, String title, Throwable t) {
        logAndSendEmail(logId, title, t, botErrorEmailAddress());
    }

    /**
     * Log exception and send email
     *
     * @see LogMailer#sendEmail(String, String, String, String[])
     */
    void logAndSendEmail(String logId, String title, String body, Throwable t, String[] addresses);

    default void logAndSendEmail(String logId, String title, String body, Throwable t) {
        logAndSendEmail(logId, title, body, t, botErrorEmailAddress());
    }

    /**
     * Send email.
     *
     * @see LogMailer#sendEmail(String, String, String, String[])
     */
    void sendEmail(String logId, String title, String body, String[] addresses);
}
