package fr.simplex_software.workshop.controllers;

import jakarta.annotation.security.*;
import org.springframework.security.core.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class K8sSbController
{
  @RolesAllowed("USER")
  @GetMapping("/hello/{who}")
  public String sayHello(@PathVariable String who, Authentication authentication)
  {
    return "Hello %s, greeted by %s".formatted(who, authentication.getName());
  }

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

  @RolesAllowed("ADMIN")
  @GetMapping("/admin")
  public String admin(Authentication authentication)
  {
    return "Hello %s, you have elevated (admin) access".formatted(authentication.getName());
  }
}
