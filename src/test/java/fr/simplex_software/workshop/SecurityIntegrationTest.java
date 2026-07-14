package fr.simplex_software.workshop;

import io.restassured.config.*;
import io.restassured.specification.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.*;
import org.springframework.test.context.*;

import javax.net.ssl.*;

import static io.restassured.RestAssured.*;
import static io.restassured.config.SSLConfig.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;

// Boots the real app on HTTPS with client-auth=need and drives it with REST
// Assured over an actual TLS handshake, presenting real client certificates -
// exercising the full chain (handshake, CN-to-principal mapping, role checks).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityIntegrationTest
{
  static TestPki pki;

  @LocalServerPort
  int port;

  @DynamicPropertySource
  static void tlsProperties(DynamicPropertyRegistry registry) throws Exception
  {
    pki = TestPki.generate();
    registry.add("server.ssl.bundle", () -> "server");
    registry.add("server.ssl.client-auth", () -> "need");
    registry.add("spring.ssl.bundle.jks.server.keystore.location", () -> "file:" + pki.serverKeystore);
    registry.add("spring.ssl.bundle.jks.server.keystore.password", () -> TestPki.PASSWORD);
    registry.add("spring.ssl.bundle.jks.server.keystore.type", () -> "JKS");
    registry.add("spring.ssl.bundle.jks.server.truststore.location", () -> "file:" + pki.truststore);
    registry.add("spring.ssl.bundle.jks.server.truststore.password", () -> TestPki.PASSWORD);
    registry.add("spring.ssl.bundle.jks.server.truststore.type", () -> "JKS");
  }

  @Test
  void adminReachesTheAdminEndpoint()
  {
    asClient(pki.adminKeystore)
      .get(url("/admin"))
      .then()
      .statusCode(200)
      .body(containsString("sb-k8s-admin"));
  }

  @Test
  void userIsForbiddenFromTheAdminEndpoint()
  {
    // Valid authenticated identity, but lacks ROLE_ADMIN: rejected in the app, not at TLS.
    asClient(pki.userKeystore)
      .get(url("/admin"))
      .then()
      .statusCode(403);
  }

  @Test
  void userCanGreetAndTheResponseNamesTheCaller()
  {
    asClient(pki.userKeystore)
      .get(url("/hello/toto"))
      .then()
      .statusCode(200)
      .body(equalTo("Hello toto, greeted by sb-k8s-user"));
  }

  @Test
  void whoamiReportsTheResolvedIdentityAndRoles()
  {
    asClient(pki.adminKeystore)
      .get(url("/whoami"))
      .then()
      .statusCode(200)
      .body("identity", equalTo("sb-k8s-admin"))
      .body("authorities", hasItems("ROLE_ADMIN", "ROLE_USER"));
  }

  @Test
  void caSignedButUnknownIdentityIsRejected()
  {
    // Clears the handshake (CA-signed) but its CN is no known user: CA trust alone isn't enough.
    asClient(pki.intruderKeystore)
      .get(url("/hello/toto"))
      .then()
      .statusCode(anyOf(is(401), is(403)));
  }

  @Test
  void withoutAClientCertificateTheHandshakeIsRefused()
  {
    // No keystore -> client-auth=need aborts the handshake before any HTTP status.
    RequestSpecification noCert = given().config(new RestAssuredConfig().sslConfig(
      sslConfig().trustStore(pki.truststore.toString(), TestPki.PASSWORD)));
    Throwable thrown = catchThrowable(() -> noCert.get(url("/hello/toto")).then().statusCode(200));
    assertThat(thrown)
      .as("a client-auth=need server must abort the handshake when no client certificate is sent")
      .isNotNull();
    assertThat(isSslFailure(thrown))
      .as("expected a TLS handshake failure, but got: %s", thrown)
      .isTrue();
  }

  private static boolean isSslFailure(Throwable thrown)
  {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause())
      if (cause instanceof SSLException)
        return true;
    return false;
  }

  private RequestSpecification asClient(java.nio.file.Path clientKeystore)
  {
    return given().config(new RestAssuredConfig().sslConfig(sslConfig()
      .keyStore(clientKeystore.toString(), TestPki.PASSWORD)
      .trustStore(pki.truststore.toString(), TestPki.PASSWORD)));
  }

  private String url(String path)
  {
    return "https://localhost:" + port + path;
  }
}
