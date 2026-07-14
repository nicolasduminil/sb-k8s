package fr.simplex_software.workshop.security;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.*;
import org.springframework.security.config.annotation.web.builders.*;
import org.springframework.security.config.annotation.web.configuration.*;
import org.springframework.security.config.annotation.web.configurers.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.*;
import org.springframework.security.web.*;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(jsr250Enabled = true)
public class SecurityConfig
{
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception
  {
    return http
      .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
      .x509(x509 -> x509
        .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
        .userDetailsService(userDetailsService()))
      .csrf(AbstractHttpConfigurer::disable)
      .build();
  }

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
