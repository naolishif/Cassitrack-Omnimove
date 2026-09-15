package it.unicas.omnimove.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import it.unicas.omnimove.security.ApiKeyFilter;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two OpenAPI documents out of one application.
 *
 * <p>The <b>partner</b> group ({@code /api/docs/partner}) describes only
 * {@code /api/partner/**}: it is the file handed to the other systems and is
 * readable without an account (see {@link SecurityConfig}). The
 * <b>internal</b> group keeps the traveller and admin API, still behind
 * authentication. Splitting them means sharing the contract of what we
 * expose on purpose without also publishing a map of what we do not.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKey";

    @Bean
    public GroupedOpenApi partnerApi() {
        return GroupedOpenApi.builder()
                .group("partner")
                .pathsToMatch("/api/partner/**")
                .addOpenApiCustomizer(openApi -> openApi
                        .info(new Info()
                                .title("OmniMove Partner API")
                                .version("v1")
                                .description("Reachability, CO₂ accounting and stop lookup for "
                                        + "the Cassino urban network. Every call carries the "
                                        + "X-Api-Key issued by the OmniMove administrators."))
                        .components(new Components().addSecuritySchemes(API_KEY_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(ApiKeyFilter.HEADER)))
                        .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME)))
                .build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
                .group("internal")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
