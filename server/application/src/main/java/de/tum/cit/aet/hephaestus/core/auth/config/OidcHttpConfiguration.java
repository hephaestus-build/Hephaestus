package de.tum.cit.aet.hephaestus.core.auth.config;

import de.tum.cit.aet.hephaestus.core.SsrfGuardedResolverGroup;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import reactor.netty.http.client.HttpClient;

/** Framework OIDC clients with the same DNS-level SSRF protection for discovery, tokens, userinfo and keys. */
@Configuration
@ConditionalOnServerRole
public class OidcHttpConfiguration {

    @Bean
    public ClientHttpRequestFactory oidcRequestFactory() {
        var factory = new ReactorClientHttpRequestFactory(
                HttpClient.create().resolver(SsrfGuardedResolverGroup.INSTANCE).followRedirect(false));
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    @Bean
    public RestOperations oidcRestOperations(@Qualifier("oidcRequestFactory") ClientHttpRequestFactory requestFactory) {
        RestTemplate template = new RestTemplate(requestFactory);
        template.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        return template;
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> loginTokenResponseClient(
            @Qualifier("oidcRequestFactory") ClientHttpRequestFactory requestFactory) {
        var guarded = new RestClientAuthorizationCodeTokenResponseClient();
        guarded.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        var existing = new RestClientAuthorizationCodeTokenResponseClient();
        return request ->
                (isOrganizational(request.getClientRegistration()) ? guarded : existing).getTokenResponse(request);
    }

    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(
            @Qualifier("oidcRestOperations") RestOperations restOperations) {
        var guardedUserInfo = new DefaultOAuth2UserService();
        guardedUserInfo.setRestOperations(restOperations);
        var guarded = new OidcUserService();
        guarded.setOauth2UserService(guardedUserInfo);
        var existing = new OidcUserService();
        return request -> (isOrganizational(request.getClientRegistration()) ? guarded : existing).loadUser(request);
    }

    @Bean
    public JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory(
            @Qualifier("oidcRestOperations") RestOperations restOperations) {
        var existing = new OidcIdTokenDecoderFactory();
        return registration -> {
            if (!isOrganizational(registration)) {
                return existing.createDecoder(registration);
            }
            var decoder = NimbusJwtDecoder.withJwkSetUri(Objects.requireNonNull(
                            registration.getProviderDetails().getJwkSetUri()))
                    .restOperations(restOperations)
                    .build();
            decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
            decoder.setJwtValidator(
                    JwtValidators.createDefaultWithValidators(List.of(new OidcIdTokenValidator(registration))));
            return decoder;
        };
    }

    private static boolean isOrganizational(ClientRegistration registration) {
        // Only generic OIDC registrations carry discovery metadata. Slack's fixed OIDC registration
        // remains on its existing framework clients; plain OAuth providers never enter this path.
        return registration.getProviderDetails().getConfigurationMetadata().containsKey("issuer");
    }
}
