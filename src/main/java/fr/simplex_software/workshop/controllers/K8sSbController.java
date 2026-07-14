package fr.simplex_software.workshop.controllers;

import jakarta.annotation.security.*;
import org.springframework.security.core.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class K8sSbController
{
  /**
   * Requires ROLE_USER. The greeting now names the authenticated caller, taken
   * from the client certificate's CN, to show the identity flowed all the way
   * through from the TLS handshake to the controller.
   */
  @RolesAllowed("USER")
  @GetMapping("/hello/{who}")
  public String sayHello(@PathVariable String who, Authentication authentication)
  {
    return "Hello %s, greeted by %s".formatted(who, authentication.getName());
  }

  /**
   * Requires ROLE_USER. Echoes the resolved identity and its granted authorities
   * - handy for confirming which certificate the server mapped the caller to.
   */
  @RolesAllowed("USER")
  @GetMapping("/whoami")
  public Map<String, Object> whoami(Authentication authentication)
  {
    return Map.of(
      "identity", authentication.getName(),
      "authorities", authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .toList());
  }

  /**
   * Requires ROLE_ADMIN. The sb-k8s-user certificate clears mTLS and is a valid
   * user, yet is rejected here with 403 - authorization keyed on identity.
   */
  @RolesAllowed("ADMIN")
  @GetMapping("/admin")
  public String admin(Authentication authentication)
  {
    return "Hello %s, you have elevated (admin) access".formatted(authentication.getName());
  }
}
