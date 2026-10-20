package app.alertify.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import app.alertify.procedures.templates.JwtNegativeTestTokensProcedureTemplate;

/** Isolated resource server with a real RSA decoder, independent of the deployed identity provider. */
class JwtNegativeTokensSecurityTest {
    @Test
    void acceptsTheOriginalAndRejectsAllThreeVariantsThroughTheSecurityFilterChain() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(ResourceServer.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            String token = signedToken(context.getBean(KeyPair.class));
            int originalStatus = mvc.perform(get("/protected").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
            assertThat(originalStatus).isEqualTo(200);
            var variants = new JwtNegativeTestTokensProcedureTemplate(token).execute(null);
            for (String name : variants.propertyNames()) {
                int status = mvc.perform(get("/protected").header("Authorization", "Bearer " + variants.path(name).stringValue()))
                        .andReturn().getResponse().getStatus();
                // Assert only status and variant name; never render requests containing tokens.
                assertThat(status).as("HTTP rejection of %s", name).isEqualTo(401);
            }
        }
    }

    private static String signedToken(KeyPair keys) throws Exception {
        String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"sub\":\"test-user\",\"exp\":" + Instant.now().plusSeconds(300).getEpochSecond() + "}");
        String input = header + "." + payload;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class ResourceServer {
        @Bean
        KeyPair keys() throws Exception {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }

        @Bean
        JwtDecoder decoder(KeyPair keys) {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .oauth2ResourceServer(server -> server.jwt(jwt -> { })).build();
        }

        @Bean
        ProtectedEndpoint endpoint() {
            return new ProtectedEndpoint();
        }
    }

    @RestController
    static class ProtectedEndpoint {
        @GetMapping("/protected")
        String read() {
            return "ok";
        }
    }
}
