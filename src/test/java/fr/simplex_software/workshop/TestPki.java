package fr.simplex_software.workshop;

import org.bouncycastle.asn1.x500.*;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.*;
import org.bouncycastle.operator.jcajce.*;

import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;

// Mints a throwaway CA hierarchy and JKS key/trust stores in a temp dir (nothing
// committed), mirroring what cert-manager does in the cluster. The intruder cert
// is CA-signed but its CN is not a known identity, so it clears the handshake yet
// is rejected by the app.
final class TestPki
{
  static final String PASSWORD = "changeit";

  final Path serverKeystore;
  final Path truststore;
  final Path adminKeystore;
  final Path userKeystore;
  final Path intruderKeystore;

  private TestPki(Path serverKeystore, Path truststore, Path adminKeystore,
                  Path userKeystore, Path intruderKeystore)
  {
    this.serverKeystore = serverKeystore;
    this.truststore = truststore;
    this.adminKeystore = adminKeystore;
    this.userKeystore = userKeystore;
    this.intruderKeystore = intruderKeystore;
  }

  static TestPki generate() throws Exception
  {
    Path dir = Files.createTempDirectory("sb-k8s-mtls-test");
    dir.toFile().deleteOnExit();

    KeyPair caKp = keyPair();
    X509Certificate caCert = selfSignedCa(caKp);

    KeyPair serverKp = keyPair();
    X509Certificate serverCert =
      sign(caKp, caCert, serverKp.getPublic(), "sb-k8s", 2, true, "localhost");

    KeyPair adminKp = keyPair();
    X509Certificate adminCert =
      sign(caKp, caCert, adminKp.getPublic(), "sb-k8s-admin", 3, false, null);

    KeyPair userKp = keyPair();
    X509Certificate userCert =
      sign(caKp, caCert, userKp.getPublic(), "sb-k8s-user", 4, false, null);

    KeyPair intruderKp = keyPair();
    X509Certificate intruderCert =
      sign(caKp, caCert, intruderKp.getPublic(), "sb-k8s-intruder", 5, false, null);

    return new TestPki(
      keyStore(dir, "server.jks", "server", serverKp.getPrivate(), serverCert, caCert),
      trustStore(dir, "truststore.jks", caCert),
      keyStore(dir, "admin.jks", "admin", adminKp.getPrivate(), adminCert, caCert),
      keyStore(dir, "user.jks", "user", userKp.getPrivate(), userCert, caCert),
      keyStore(dir, "intruder.jks", "intruder", intruderKp.getPrivate(), intruderCert, caCert));
  }

  private static KeyPair keyPair() throws Exception
  {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static X509Certificate selfSignedCa(KeyPair caKp) throws Exception
  {
    X500Name name = new X500Name("CN=sb-k8s-test-ca");
    X509v3CertificateBuilder builder = certBuilder(name, BigInteger.ONE, name, caKp.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    builder.addExtension(Extension.keyUsage, true,
      new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
    return convert(builder, caKp.getPrivate());
  }

  private static X509Certificate sign(KeyPair issuer, X509Certificate issuerCert,
                                      PublicKey subjectKey, String cn, long serial,
                                      boolean serverAuth, String dnsSan) throws Exception
  {
    X500Name issuerName = new JcaX509CertificateHolder(issuerCert).getSubject();
    X509v3CertificateBuilder builder =
      certBuilder(issuerName, BigInteger.valueOf(serial), new X500Name("CN=" + cn), subjectKey);
    builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
    builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
      serverAuth ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
    if (dnsSan != null)
      builder.addExtension(Extension.subjectAlternativeName, false,
        new GeneralNames(new GeneralName(GeneralName.dNSName, dnsSan)));
    return convert(builder, issuer.getPrivate());
  }

  private static X509v3CertificateBuilder certBuilder(X500Name issuer, BigInteger serial,
                                                      X500Name subject, PublicKey subjectKey)
  {
    Instant now = Instant.now();
    return new JcaX509v3CertificateBuilder(issuer, serial,
      Date.from(now.minus(Duration.ofDays(1))),
      Date.from(now.plus(Duration.ofDays(3650))),
      subject, subjectKey);
  }

  private static X509Certificate convert(X509v3CertificateBuilder builder, PrivateKey signingKey)
    throws OperatorCreationException, CertificateException
  {
    ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(signingKey);
    return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
  }

  private static Path keyStore(Path dir, String file, String alias, PrivateKey key,
                               X509Certificate leaf, X509Certificate ca) throws Exception
  {
    KeyStore ks = KeyStore.getInstance("JKS");
    ks.load(null, null);
    ks.setKeyEntry(alias, key, PASSWORD.toCharArray(), new X509Certificate[]{leaf, ca});
    return persist(dir, file, ks);
  }

  private static Path trustStore(Path dir, String file, X509Certificate ca) throws Exception
  {
    KeyStore ks = KeyStore.getInstance("JKS");
    ks.load(null, null);
    ks.setCertificateEntry("ca", ca);
    return persist(dir, file, ks);
  }

  private static Path persist(Path dir, String file, KeyStore ks) throws Exception
  {
    Path path = dir.resolve(file);
    try (OutputStream os = Files.newOutputStream(path))
    {
      ks.store(os, PASSWORD.toCharArray());
    }
    path.toFile().deleteOnExit();
    return path;
  }
}
