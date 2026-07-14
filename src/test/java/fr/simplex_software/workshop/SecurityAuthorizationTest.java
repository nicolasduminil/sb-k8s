package fr.simplex_software.workshop;

import fr.simplex_software.workshop.controllers.*;
import fr.simplex_software.workshop.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the identity-based authorization rules independently of the real
 * certificates: the {@code user()} post-processor stands in for the principal
 * that X.509 would have resolved from a client certificate's CN.
 */
@WebMvcTest(K8sSbController.class)
@Import(SecurityConfig.class)
class SecurityAuthorizationTest
{
  @Autowired
  MockMvc mockMvc;

  @Test
  void anonymousIsRejected() throws Exception
  {
    mockMvc.perform(get("/hello/toto"))
      .andExpect(status().is4xxClientError());
  }

  @Test
  void userCanGreetAndReportsItsIdentity() throws Exception
  {
    mockMvc.perform(get("/hello/toto").with(user("sb-k8s-user").roles("USER")))
      .andExpect(status().isOk())
      .andExpect(content().string("Hello toto, greeted by sb-k8s-user"));
  }

  @Test
  void userIsForbiddenFromAdminEndpoint() throws Exception
  {
    mockMvc.perform(get("/admin").with(user("sb-k8s-user").roles("USER")))
      .andExpect(status().isForbidden());
  }

  @Test
  void adminCanReachAdminEndpoint() throws Exception
  {
    mockMvc.perform(get("/admin").with(user("sb-k8s-admin").roles("ADMIN", "USER")))
      .andExpect(status().isOk());
  }
}
