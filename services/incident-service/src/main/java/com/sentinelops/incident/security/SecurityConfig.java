package com.sentinelops.incident.security;

import java.util.List;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Authentication and authorization (Phase 7): this service is an OAuth 2.0 resource server that
 * trusts JWTs issued by the configured Keycloak realm.
 *
 * <p><b>CSRF:</b> disabled. This is a stateless bearer-token API — every request authenticates with
 * an {@code Authorization: Bearer <token>} header, never a browser-managed session cookie, so there
 * is no ambient credential for a cross-site request to ride on. CSRF protection exists to defend
 * cookie-based session auth; it does not apply here (see docs/development/security.md).
 *
 * <p><b>Two filter chains:</b> the first (highest precedence) covers only {@code
 * /actuator/prometheus} and uses HTTP Basic with a single local static credential, because the
 * local Prometheus server cannot perform an OAuth2 client-credentials exchange — this keeps that
 * endpoint authenticated rather than anonymous while staying free/local. The second covers
 * everything else and validates OAuth2 bearer JWTs.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

  private static final String[] PUBLIC_PATHS = {
    "/actuator/health", "/actuator/health/**", "/actuator/info"
  };

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain metricsFilterChain(
      HttpSecurity http, SecurityProperties securityProperties) throws Exception {
    http.securityMatcher("/actuator/prometheus")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .httpBasic(withDefaults -> {})
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    return http.build();
  }

  @Bean
  public InMemoryUserDetailsManager metricsUserDetailsManager(
      SecurityProperties securityProperties) {
    PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    UserDetails metricsUser =
        User.withUsername(securityProperties.metrics().username())
            .password(encoder.encode(securityProperties.metrics().password()))
            .roles("METRICS")
            .build();
    return new InMemoryUserDetailsManager(metricsUser);
  }

  /**
   * Phase 12: alert-connector webhooks (Alertmanager, the generic HMAC-signed connector)
   * authenticate themselves — a static shared bearer token or an HMAC signature, verified inside
   * the controller/verifier, never a JWT — so this chain permits every request through Spring
   * Security itself and lets the controller reject unauthenticated ones with 401. This never
   * weakens the interactive JWT chain below: it is scoped to exactly {@code
   * /api/v1/alerts/webhooks/**} via {@code securityMatcher}, so no other endpoint is affected.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 1)
  public SecurityFilterChain alertWebhookFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/v1/alerts/webhooks/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    return http.build();
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 3)
  public SecurityFilterChain apiFilterChain(
      HttpSecurity http,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler,
      CorsConfigurationSource corsConfigurationSource)
      throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    // Defense in depth alongside AdminController's own
                    // @PreAuthorize("hasRole('ADMIN')").
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                    .authenticationEntryPoint(authenticationEntryPoint))
        .exceptionHandling(handling -> handling.accessDeniedHandler(accessDeniedHandler));
    return http.build();
  }

  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new JwtRoleConverter());
    return converter;
  }

  /**
   * Deliberately built from the JWKS endpoint address rather than {@code
   * NimbusJwtDecoder.withIssuerLocation(...)}. In the local Compose environment, the address this
   * service reaches Keycloak's signing keys at (the internal {@code keycloak:8080} container
   * address) is not the same address Keycloak reports as its own issuer in every token it mints (a
   * fixed, externally-reachable {@code KC_HOSTNAME}) — using {@code withIssuerLocation} would fail
   * its own issuer/discovery-document consistency check. Fetching keys and validating the issuer
   * claim as two independent steps is the standard fix (see docs/development/security.md).
   */
  @Bean
  public JwtDecoder jwtDecoder(
      OAuth2ResourceServerProperties resourceServerProperties,
      SecurityProperties securityProperties) {
    String jwkSetUri = resourceServerProperties.getJwt().getJwkSetUri();
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

    OAuth2TokenValidator<Jwt> defaultValidator =
        JwtValidators.createDefaultWithIssuer(securityProperties.issuer());
    OAuth2TokenValidator<Jwt> audienceValidator =
        new JwtAudienceValidator(securityProperties.audience());
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator));
    return decoder;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource(SecurityProperties securityProperties) {
    List<String> allowedOrigins =
        securityProperties.corsAllowedOrigins().stream()
            .filter(origin -> origin != null && !origin.isBlank())
            .toList();

    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-ID"));
    configuration.setExposedHeaders(List.of("X-Correlation-ID", "Location"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(java.time.Duration.ofHours(1));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }
}
