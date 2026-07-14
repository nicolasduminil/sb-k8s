package fr.simplex_software.workshop;

import fr.simplex_software.workshop.controllers.*;
import fr.simplex_software.workshop.security.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.security.test.context.support.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.*;
import org.springframework.web.context.*;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Tier 1: fast authorization-logic check, no cluster. @WithUserDetails loads the
// principal from the real UserDetailsService, so the CN->roles mapping is
// exercised; only the actual TLS/certificate handshake is out of scope (see
// SecurityE2eIT).
@WebMvcTest(K8sSbController.class)
@Import(SecurityConfig.class)
class SecurityAuthorizationTest
{
  MockMvc mockMvc;

  @BeforeEach
  void setUp(WebApplicationContext context)
  {
    // Build MockMvc with springSecurity() so @WithUserDetails' test principal is
    // bridged into the request (the auto-configured MockMvc omits that).
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void anonymousIsRejected() throws Exception
  {
    mockMvc.perform(get("/hello/toto")).andExpect(status().is4xxClientError());
  }

  @Test
  @WithUserDetails("sb-k8s-user")
  void userCanGreetAndTheResponseNamesTheCaller() throws Exception
  {
    mockMvc.perform(get("/hello/toto"))
      .andExpect(status().isOk())
      .andExpect(content().string("Hello toto, greeted by sb-k8s-user"));
  }

  @Test
  @WithUserDetails("sb-k8s-user")
  void userIsForbiddenFromTheAdminEndpoint() throws Exception
  {
    mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
  }

  @Test
  @WithUserDetails("sb-k8s-admin")
  void adminReachesTheAdminEndpoint() throws Exception
  {
    mockMvc.perform(get("/admin"))
      .andExpect(status().isOk())
      .andExpect(content().string(containsString("sb-k8s-admin")));
  }

  @Test
  @WithUserDetails("sb-k8s-admin")
  void whoamiReportsTheResolvedIdentityAndRoles() throws Exception
  {
    mockMvc.perform(get("/whoami"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.identity").value("sb-k8s-admin"))
      .andExpect(jsonPath("$.authorities", hasItems("ROLE_ADMIN", "ROLE_USER")));
  }
}
