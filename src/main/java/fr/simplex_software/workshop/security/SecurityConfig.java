package fr.simplex_software.workshop.security;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.*;
import org.springframework.security.config.annotation.web.builders.*;
import org.springframework.security.config.annotation.web.configuration.*;
import org.springframework.security.config.annotation.web.configurers.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.*;
import org.springframework.security.web.*;

/**
 * Turns the transport-level mTLS handshake into an authenticated, authorized
 * application identity.
 *
 * <p>On the {@code mtls} branch the server merely required <em>a</em> client
 * certificate signed by the trusted CA ({@code server.ssl.client-auth = need}):
 * every CA-signed caller was equally, anonymously accepted. Here Spring Security
 * X.509 reads the client certificate's subject Common Name and maps it, through
 * {@link #userDetailsService()}, to a named principal with roles. The certificate
 * is no longer just a key to the door - it <em>is</em> the user's identity.</p>
 *
 * <p>The mapping is: {@code CN=sb-k8s-admin} -&gt; ROLE_ADMIN + ROLE_USER,
 * {@code CN=sb-k8s-user} -&gt; ROLE_USER. A certificate that is CA-signed (so it
 * clears the TLS handshake) but whose CN is unknown here is rejected at the
 * authentication layer with 401 - proving the app authorizes on identity, not
 * merely on CA trust.</p>
 */
@Configuration
@EnableWebSecurity
// Enable JSR-250 (@RolesAllowed / @PermitAll / @DenyAll) so the controller
// endpoints declare their own required roles next to the code they protect,
// rather than in a central request-matcher list. @RolesAllowed("ADMIN") maps to
// the ROLE_ADMIN authority (the default role prefix).
@EnableMethodSecurity(jsr250Enabled = true)
public class SecurityConfig
{
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception
  {
    return http
      // Per-endpoint role checks live on the controller as JSR-250 annotations.
      // The filter chain only sets the baseline: actuator's endpoints are not our
      // own methods (so they cannot carry annotations) and everything else must at
      // least be an authenticated, certificate-resolved identity.
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health/**").permitAll()
        .requestMatchers("/actuator/**").hasRole("ADMIN")
        .anyRequest().authenticated())
      // Extract the principal from the certificate subject's CN, then resolve it
      // to a UserDetails (roles). This is the "user identity based" validation.
      .x509(x509 -> x509
        .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
        .userDetailsService(userDetailsService()))
      // Stateless, certificate-authenticated API: no login form, no CSRF token.
      .csrf(AbstractHttpConfigurer::disable)
      .build();
  }

  /**
   * The registry of known certificate identities. Passwords are irrelevant under
   * X.509 authentication (the certificate is the credential), but UserDetails
   * requires a non-null value, hence the {@code {noop}} placeholder.
   */
  @Bean
  public UserDetailsService userDetailsService()
  {
    UserDetails admin = User.withUsername("sb-k8s-admin")
      .password("{noop}unused")
      .roles("ADMIN", "USER")
      .build();
    UserDetails user = User.withUsername("sb-k8s-user")
      .password("{noop}unused")
      .roles("USER")
      .build();
    return new InMemoryUserDetailsManager(admin, user);
  }
}
