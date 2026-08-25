package com.docvalidate.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI docValidateApi() {
        return new OpenAPI().info(new Info()
                .title("DocValidate API")
                .version("v1")
                .description("""
                        Asynchronous document validation. Create a request, upload the bytes, \
                        then poll until the status is terminal.

                        Errors are RFC 9457 problem responses carrying a stable `code`; branch on \
                        that rather than on the status, since 409 covers several distinct cases.

                        Idempotency: `Idempotency-Key` on create returns the same request rather \
                        than a second one, and re-uploading identical bytes is accepted with no \
                        state change.""")
                .license(new License().name("MIT")));
    }
}
