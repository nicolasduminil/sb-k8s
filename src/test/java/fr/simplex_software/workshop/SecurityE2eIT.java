package fr.simplex_software.workshop;

import io.restassured.config.*;
import io.restassured.specification.*;
import org.junit.jupiter.api.*;

import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;

import static io.restassured.RestAssured.*;
import static io.restassured.config.SSLConfig.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.*;

class SecurityE2eIT
{
  static final String PASSWORD = "changeit";
  static final String BASE_URL = "https://localhost:8443";

  @BeforeAll
  static void requireRunningCluster()
  {
    assumeTrue(
      Path.of("truststore.p12").toFile().exists()
        && Path.of("admin.p12").toFile().exists()
        && Path.of("user.p12").toFile().exists()
        && Path.of("intruder.p12").toFile().exists(),
      "e2e skipped: keystores not found - run ./start-all.sh or ./redeploy.sh mtls-security first");
    assumeTrue(portIsOpen("localhost", 8443),
      "e2e skipped: nothing on localhost:8443 - start `kubectl port-forward svc/sb-k8s 8443:8443`");
  }

  @Test
  void adminReachesTheAdminEndpoint()
  {
    asClient("admin.p12").get(BASE_URL + "/admin")
      .then().statusCode(200).body(containsString("sb-k8s-admin"));
  }

  @Test
  void userIsForbiddenFromTheAdminEndpoint()
  {
    asClient("user.p12").get(BASE_URL + "/admin").then().statusCode(403);
  }

  @Test
  void userCanGreetAndTheResponseNamesTheCaller()
  {
    asClient("user.p12").get(BASE_URL + "/hello/toto")
      .then().statusCode(200).body(equalTo("Hello toto, greeted by sb-k8s-user"));
  }

  @Test
  void whoamiReportsTheResolvedIdentityAndRoles()
  {
    asClient("admin.p12").get(BASE_URL + "/whoami")
      .then().statusCode(200)
      .body("identity", equalTo("sb-k8s-admin"))
      .body("authorities", hasItems("ROLE_ADMIN", "ROLE_USER"));
  }

  @Test
  void caSignedButUnknownIdentityIsRejected()
  {
    asClient("intruder.p12").get(BASE_URL + "/hello/toto")
      .then().statusCode(anyOf(is(401), is(403)));
  }

  @Test
  void withoutAClientCertificateTheHandshakeIsRefused()
  {
    RequestSpecification noCert = given().config(new RestAssuredConfig().sslConfig(
      sslConfig().trustStore("truststore.p12", PASSWORD).trustStoreType("PKCS12")));
    Throwable thrown = catchThrowable(() -> noCert.get(BASE_URL + "/hello/toto").then().statusCode(200));
    assertThat(thrown)
      .as("a client-auth=need server must abort the handshake when no client certificate is sent")
      .isNotNull();
    assertThat(isSslFailure(thrown))
      .as("expected a TLS handshake failure, but got: %s", thrown)
      .isTrue();
  }

  private static RequestSpecification asClient(String keystore)
  {
    return given().config(new RestAssuredConfig().sslConfig(sslConfig()
      .keyStore(keystore, PASSWORD).keystoreType("PKCS12")
      .trustStore("truststore.p12", PASSWORD).trustStoreType("PKCS12")));
  }

  private static boolean portIsOpen(String host, int port)
  {
    try (Socket socket = new Socket())
    {
      socket.connect(new InetSocketAddress(host, port), 800);
      return true;
    }
    catch (Exception e)
    {
      return false;
    }
  }

  private static boolean isSslFailure(Throwable thrown)
  {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause())
      if (cause instanceof SSLException)
        return true;
    return false;
  }
}
