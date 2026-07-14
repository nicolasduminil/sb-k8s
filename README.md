# From Spring Boot to K8S

> This section documents the `mtls-security` branch, which builds on the `mtls`
> branch. Everything already described for mutual TLS still applies (the CA
> hierarchy, `server.ssl.client-auth = need`, certificate rotation, …); on top of
> it this branch adds **Spring Security X.509**, so the client certificate is no
> longer just a key to the door — it becomes the caller's authenticated identity,
> and access is authorized on it. The mTLS material below is retained as the
> foundation this branch stands on; the new layer is described in
> [Identity-based authorization with Spring Security X.509](#identity-based-authorization-with-spring-security-x509).
> 

# Mutual TLS (mTLS)

The `cert-manager` branch uses one-way TLS: the server presents a
certificate, the connection is encrypted, but *anyone* who can reach the socket
gets served. The `mtls` branch turns on mutual TLS, so the server also
requires the *client* to present a certificate signed by a trusted CA. A caller
without a valid client certificate is rejected during the TLS handshake. This 
authentication happens at the transport layer.

## Why a CA hierarchy is required

Plain mutual TLS cannot work with a single *self-signed* issuer: every
certificate would be its own, unrelated root, so the server would have no way to
validate a client certificate. This branch therefore introduces a tiny in-cluster
PKI (in `k8s/issuers.yaml`):

  1. a self-signed issuer named `selfsigned-issuer` is used only to bootstrap the CA;
  2. a CA certificate named `sb-k8s-ca` with `isCA: true` is the shared root of trust;
  3. a CA issuer named `ca-issuer` signs both the server and the client certificates.

Because both certificates share the same CA, the server's truststore, which
`cert-manager` initializes with that CA, validates the client certificate, and vice
versa. Namespaced `Issuer`s are used rather than `ClusterIssuer`s so the CA secret
stays in the `default` namespace. A CA `ClusterIssuer` would expect it in
`cert-manager`'s cluster-resource namespace instead.

## What changed compared to the `cert-manager` branch

| Artifact | Change |
|----------|--------|
| `k8s/issuers.yaml` | New — replaces `cluster-issuer.yaml`. Holds the self-signed `Issuer`, the CA `Certificate`, and the CA `Issuer` described above. |
| `k8s/certificate.yaml` | The server certificate is now signed by `ca-issuer` (was the self-signed issuer) and declares `usages: [server auth, …]`. |
| `k8s/client-certificate.yaml` | New — a client certificate (`sb-k8s-client`, `usages: [client auth, …]`) signed by the same `ca-issuer`, stored as PEM in the `sb-k8s-client-cert` secret. |
| `k8s/configmap.yaml` | Adds `server.ssl.client-auth = need`, which makes a client certificate mandatory. |
| `skaffold.yaml` | Deploys `issuers.yaml` and `client-certificate.yaml`; no longer references `cluster-issuer.yaml`. |

Deploy it exactly as before:

    $ skaffold run

and confirm all three certificates are issued:

    $ kubectl get certificate

    NAME            READY   SECRET               AGE
    sb-k8s          True    sb-k8s-cert          1m
    sb-k8s-ca       True    sb-k8s-ca            1m
    sb-k8s-client   True    sb-k8s-client-cert   1m

## Testing mTLS

First extract the client certificate, its key and the CA from the `sb-k8s-client-cert`
secret so `curl` can use them:

    $ kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.tls\.crt}' | base64 -d > client.crt
    $ kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.tls\.key}' | base64 -d > client.key
    $ kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.ca\.crt}'  | base64 -d > ca.crt

Port-forward the service (leave it running in its own terminal):

    $ kubectl port-forward svc/sb-k8s 8443:8443

### The quick way (skips server verification)

You can pass `-k` (or `--insecure`) to skip verifying the *server's* certificate.
On this branch you still have to send the *client* certificate as `-k` only turns
off the client-side identity check of the server, it does not disable TLS or
waive the server's `client-auth = need` requirement:

    $ curl -k --cert client.crt --key client.key https://localhost:8443/hello/toto
    Hello toto

### The proper way (verifies the server too)

`-k` is not required since the server certificate is verifiable, it is just signed by
a CA (`sb-k8s-ca`) that your machine does not trust by default. `cert-manager` writes
that CA into the `ca.crt` extracted above, so pass it with `--cacert` to validate
the server for real. The certificate has a `localhost` SAN, which is why
`localhost` is accepted:

    $ curl --cacert ca.crt --cert client.crt --key client.key https://localhost:8443/hello/toto
    Hello toto

If you point `curl` at a CA that did *not* sign the server certificate or omit
`--cacert` so it falls back to the system trust store, then the request fails with
`unable to get local issuer certificate`. That is the proof the server is really
being verified.

### The mTLS check (client authentication)

Now drop the client certificate but keep verifying the server. The server aborts
the handshake because `client-auth = need`:

    $ curl --cacert ca.crt https://localhost:8443/hello/toto
    curl: (56) OpenSSL SSL_read: ... alert certificate required

That rejection is the proof that mutual TLS is enforced: only callers holding a
certificate signed by `sb-k8s-ca` are allowed through.

### Trusting the CA machine-wide (optional)

To avoid passing `--cacert` every time, install the CA into your operating
system's trust store once (you still need `--cert`/`--key` for client auth):

    $ sudo cp ca.crt /usr/local/share/ca-certificates/sb-k8s-ca.crt
    $ sudo update-ca-certificates
    $ curl --cert client.crt --key client.key https://localhost:8443/hello/toto
    Hello toto


# Identity-based authorization with Spring Security X.509

On the `mtls` branch authentication stopped at the transport layer: the server
accepted or refused the TLS handshake, but the application itself had no idea
*who* the caller was — every CA-signed certificate was equally, anonymously
accepted. This branch closes that gap. Spring Security reads the client
certificate that already cleared the handshake, extracts its subject Common Name
and maps it to a named principal with roles. The certificate stops being a mere
door key and becomes the user's identity; endpoints are then authorized on that
identity.

## How it works

The mTLS handshake is unchanged — `server.ssl.client-auth = need` still requires
a CA-signed client certificate. What is new is a Spring Security filter chain
(`SecurityConfig`) configured for X.509 authentication:

  - `x509().subjectPrincipalRegex("CN=(.*?)(?:,|$)")` pulls the CN out of the
    certificate subject as the principal name;
  - a `UserDetailsService` resolves that name to roles:
    `CN=sb-k8s-admin` → `ROLE_ADMIN` + `ROLE_USER`, `CN=sb-k8s-user` → `ROLE_USER`;
  - per-endpoint role checks are declared with **JSR-250 annotations** on the
    controller (`@EnableMethodSecurity(jsr250Enabled = true)`): `@RolesAllowed("ADMIN")`
    on `/admin`, `@RolesAllowed("USER")` on `/hello` and `/whoami`;
  - the filter chain carries a single baseline rule,
    `anyRequest().authenticated()`, so every call is at least a
    certificate-resolved identity; the roles are enforced by the annotations.

The decisive consequence: a certificate that is CA-signed (so it clears the
handshake) but whose CN is **not** in the `UserDetailsService` is rejected at the
authentication layer — CA trust alone is no longer enough.

## What changed compared to the `mtls` branch

| Artifact | Change |
|----------|--------|
| `pom.xml` | Adds `spring-boot-starter-security`, plus `rest-assured` and `bouncycastle` (`bcpkix`) for the HTTPS integration test (Groovy is pinned to 4.0.x there, the line REST Assured expects). |
| `SecurityConfig.java` | New — the X.509 filter chain (baseline `anyRequest().authenticated()`), CN→principal regex, the in-memory identity registry and `@EnableMethodSecurity(jsr250Enabled = true)`. |
| `K8sSbController.java` | `/hello/{who}` now names the authenticated caller; adds `/whoami` (echoes the resolved identity + authorities) and an admin-only `/admin`. Roles are enforced with JSR-250 `@RolesAllowed`. |
| `k8s/client-certificate.yaml` | Now issues **two** client certificates — `sb-k8s-admin` and `sb-k8s-user` (different CNs) — into `sb-k8s-admin-cert` / `sb-k8s-user-cert`, replacing the single `sb-k8s-client`. |
| `skaffold.yaml` / `redeploy.sh` / `start-all.sh` | The `reset` hook deletes the two new secrets, and the scripts extract `admin.crt/key` and `user.crt/key`. |

## Testing identity-based authorization

Deploy and extract both identities (`redeploy.sh` does this automatically on this
branch):

    $ ./redeploy.sh mtls-security
    $ kubectl port-forward svc/sb-k8s 8443:8443      # or: skaffold dev --port-forward

Confirm the two client certificates are issued:

    $ kubectl get certificate
    NAME           READY   SECRET              AGE
    sb-k8s         True    sb-k8s-cert         1m
    sb-k8s-ca      True    sb-k8s-ca           1m
    sb-k8s-admin   True    sb-k8s-admin-cert   1m
    sb-k8s-user    True    sb-k8s-user-cert    1m

Either identity can greet, and the response names the caller — the CN travelled
all the way from the certificate to the controller:

    $ curl --cacert ca.crt --cert user.crt --key user.key https://localhost:8443/hello/toto
    Hello toto, greeted by sb-k8s-user

    $ curl --cacert ca.crt --cert admin.crt --key admin.key https://localhost:8443/whoami
    {"identity":"sb-k8s-admin","authorities":["ROLE_ADMIN","ROLE_USER"]}

Only the admin identity reaches the admin-only endpoint. The user certificate is
a perfectly valid, CA-signed, authenticated identity — it simply lacks the role,
so it is rejected with `403 Forbidden` **inside the application**, not at the
handshake:

    $ curl --cacert ca.crt --cert admin.crt --key admin.key https://localhost:8443/admin
    Hello sb-k8s-admin, you have elevated (admin) access

    $ curl --cacert ca.crt --cert user.crt --key user.key https://localhost:8443/admin
    {"status":403,"error":"Forbidden", ...}

That 403 is the whole point of this branch: two callers that are indistinguishable
to plain mTLS (both hold a CA-signed certificate) are told apart by *identity*.
Dropping the client certificate entirely still fails at the handshake exactly as
on the `mtls` branch, since `client-auth = need` is unchanged.

All of this is covered without a cluster by `SecurityIntegrationTest`
(`mvn test`). It is a genuine integration test: it boots the app on HTTPS with
`client-auth = need` and drives it with **REST Assured** over a real TLS
handshake, presenting actual client certificates. A throwaway CA hierarchy and
the JKS key/trust stores are minted in-memory by `TestPki` (via BouncyCastle),
mirroring what cert-manager does in the cluster — so the test exercises the whole
chain end to end, including the two cases a mocked principal cannot reach: a
missing certificate (handshake refused) and a CA-signed certificate with an
unknown CN (rejected by the app).

## Certificate renewal and rotation

`cert-manager` owns the whole lifecycle of the server certificate, not just its first
issuance. `renewBefore` on `k8s/certificate.yaml` says how long before expiry a
replacement is issued. When that moment arrives cert-manager signs a new server
certificate from `ca-issuer`, rebuilds the JKS keystore and truststore, and
overwrites the `sb-k8s-cert` secret in place. The secret name never changes, only
its contents.

The property that makes this branch interesting is its stable CA. The server
leaf is signed by `ca-issuer`, which is backed by the long-lived `sb-k8s-ca`
certificate (see [Why a CA hierarchy is required](#why-a-ca-hierarchy-is-required)).
So while the server leaf rotates, `ca.crt`, as the CA the client pins with `--cacert`,
does not change. That is exactly what lets a client keep trusting the connection
across a rotation, and it is the difference from the self-signed `cert-manager`
branch, where `ca.crt` rotates together with the leaf and this whole walkthrough is
impossible.

### Trying it end to end

The steps below are the runnable version of everything in this section, framed as
two experiments: 

  - without in-place reload the pod eventually breaks; 
  - with it, the pod keeps working. 

Here are the steps:

1. With the cluster running, as documented on the `master` branch, shorten the server certificate so it expires within the hour by adding `duration: 1h` to `k8s/certificate.yaml` (it already carries `renewBefore: 5m`):

       spec:
         duration: 1h
         renewBefore: 5m

2. If you manually have activated the port forwarding then kill it. Then, redeploy by running `redeploy.sh`:

       $ ./redeploy.sh mtls-security

3. Forward the port either manually, as shown below, or use `skaffold dev --port-forward`, which survives pod restarts:

       $ kubectl port-forward svc/sb-k8s 8443:8443

4. Confirm that the `curl` request below works. 

       $ curl --cacert ca.crt --cert admin.crt --key admin.key https://localhost:8443/hello/toto
       Hello toto, greeted by sb-k8s-admin

**Experiment A — no `reload-on-update`: the pod breaks after an hour**

5. Go to do something else and come back after an hour. `cert-manager` rotated
   the secret ~5 minutes before expiry, but the running app never reloaded it and is
   still serving the certificate that it have read at startup, which has now expired, so the same call fails:

       $ curl --cacert ca.crt --cert admin.crt --key admin.key \
           https://localhost:8443/hello/toto
       curl: (60) SSL certificate problem: certificate has expired

**Experiment B — with `reload-on-update`: the pod keeps working**

6. Enable in-place reload by adding to the bundle in `k8s/configmap.yaml`:

       spring.ssl.bundle.jks.server.reload-on-update = true

7. Redeploy and forward again:

       $ ./redeploy.sh mtls-security
       $ kubectl port-forward svc/sb-k8s 8443:8443

8. Come back again after an hour and repeat the exact same call. This time the
   server reloaded the rotated certificate in place, and because `ca.crt` (the CA)
   never changed, the unchanged client command still succeeds:

       $ curl --cacert ca.crt --cert admin.crt --key admin.key https://localhost:8443/hello/toto
       Hello toto, greeted by sb-k8s-admin

The subsections below explain each moving part.

### Watching it happen

By default, `cert-manager` issues a 90-day certificate, so nothing visibly rotates
during a test session. To watch a full cycle on a human timescale, after having
shortened the server certificate's lifetime, as explained above, look at the
serial inside the issued certificate:

    $ kubectl get secret sb-k8s-cert -o jsonpath='{.data.tls\.crt}' \
        | base64 -d | openssl x509 -noout -serial -dates

You'll se two timestamps:

  - `notAfter` is the expiry; 
  - `renewalTime` is when cert-manager plans to rotate.

This serial is the cleanest rotation fingerprint. It changes on every renewal, 
while `ca.crt` stays the same.


### Does the running application pick up the new certificate?

Rotating the secret is only half the story. The pod mounts `keystore.jks` and
`truststore.jks` from that secret (see `CERT_PATH` in `k8s/deployment.yaml`), and
two things stand between a rotated secret and a server that actually serves the new
certificate.

First, `kubelet` refreshes the mounted files a short while after the secret changes
(up to ~60–90 s), not instantly. Second, and more importantly, Spring Boot reads
the keystore when it builds its SSL context. According to the property 
`server.ssl.bundle = server` in `k8s/configmap.yaml`, this project configures 
TLS through an SSL bundle, rather than the classic `server.ssl.key-store`, and 
that matters: SSL bundles can be reloaded without a restart. 

Add the following property to the `configmap.yaml`:

    spring.ssl.bundle.jks.server.reload-on-update = true

and Spring Boot watches the keystore and truststore files and rebuilds the SSL
context in place when `cert-manager` rotates them, so the new certificate is served
with no downtime. Without that property, the bundle is read once at startup and a
rotated secret is ignored until the pod restarts:

    $ kubectl rollout restart deployment/sb-k8s

To verify end-to-end whether the server presents the new certificate, compare 
the currently served against the old one. In order to do that, before rotating, 
with the port-forward still running, record the current serial and
keep a copy of the leaf:

    $ echo | openssl s_client -connect localhost:8443 2>/dev/null \
        | openssl x509 -noout -serial -enddate         # note the serial + notAfter
    $ echo | openssl s_client -connect localhost:8443 2>/dev/null \
        | openssl x509 > old-cert.pem                  # keep the old leaf

Wait for `renewBefore` to fire and repeat. Read the wire again:

    $ echo | openssl s_client -connect localhost:8443 2>/dev/null \
        | openssl x509 -noout -serial -enddate

The renewed certificate works when this handshake still succeeds and the
serial has changed to a later `notAfter`. Confirm a real mTLS request still goes
through with the same `ca.crt` and client certificate as before:

    $ curl --cacert ca.crt --cert admin.crt --key admin.key \
        https://localhost:8443/hello/toto
    Hello toto, greeted by sb-k8s-admin

To confirm that the old certificate is retired:

    $ openssl x509 -in old-cert.pem -noout -checkend 0    # "is it expired right now?"
    Certificate expired

    $ openssl verify -CAfile ca.crt old-cert.pem          # validate old leaf against the CA
    old-cert.pem: ... certificate has expired

As opposed to before, when the old one-hour certificate reaches its `notAfter`, 
both commands still report it valid. Now, once it expires they flip as shown above.

### Watching an expired certificate get rejected

The checks above validate a saved file; the more convincing proof is a live
handshake refused because the certificate on the wire has expired. That happens
naturally when `reload-on-update` is *not* enabled: the server keeps serving the
certificate it read at startup and ignores the rotated secret, so once that
in-memory certificate passes its one-hour `notAfter` the running server is presenting
an expired certificate. From then on the client rejects it:

    $ curl --cacert ca.crt --cert admin.crt --key admin.key \
        https://localhost:8443/hello/toto
    curl: (60) SSL certificate problem: certificate has expired

    $ echo | openssl s_client -connect localhost:8443 2>/dev/null | grep -i verify
    Verify return code: 10 (certificate has expired)

(The exact wording varies a little between curl/OpenSSL versions.) This is the
concrete reason to enable `reload-on-update` or restart the pod on rotation: left
alone, a long-running pod will eventually serve a stale, expired certificate and
break TLS on its own — even though cert-manager rotated the secret an hour earlier.

> **The client certificates rotate too.** `sb-k8s-admin` and `sb-k8s-user` renew
> on their own lifecycle, and because `client-auth = need` the *server* validates
> them against its truststore on every handshake. An expired client certificate is
> refused exactly like the missing one in
> [The mTLS check](#the-mtls-check-client-authentication) — the mutual counterpart
> of the rejection above.

## Switching branches and redeploying

This repository has more than one branch: `cert-manager`, `mtls`, `mtls-security`, …. Switching
between them is a *cluster* operation. `minikube` and `cert-manager` stay up the
whole time, only the application's own resources are recreated. In particular 
there is no need to stop/restart minikube or to build by hand since `skaffold run`
does it in one step.

The `redeploy.sh` helper automates the full sequence:

  - tear down:
  - optionally switch branch;
  - rebuild and redeploy;
  - re-extract `ca.crt` and, on `mtls`, the client certificate, or on
    `mtls-security`, the `admin` and `user` client certificates.


    $ ./redeploy.sh               # redeploy the current branch
    $ ./redeploy.sh mtls-security # switch to mtls-security, then redeploy
    $ ./redeploy.sh mtls          # switch to mtls, then redeploy
    $ ./redeploy.sh cert-manager  # switch to cert-manager, then redeploy

Under the hood it runs `skaffold delete`, `git checkout <branch>`, then
`skaffold run -p reset`. The `reset` profile, defined in `skaffold.yaml`,
deletes the `cert-manager`-generated secrets *before* deploying, so the new branch
gets freshly issued certificates. This matters when switching branches because
those secrets are not garbage-collected on their own and their contents differ
between branches.

Two things the script takes care of that are easy to forget by hand: 

  - the `ca.crt` changes between branches (different issuer), so it must be
    re-extracted;
  - a manual `kubectl port-forward` dies with the old pod and has to be restarted. Using `skaffold dev --port-forward` avoids that entirely.

## `cert-manager` vs `mtls`: the subtle difference

The `cert-manager` and `mtls` branches look almost identical: same cert-manager,
same JKS keystore, same Skaffold flow, HTTPS on 8443. Yet they answer two
different questions, and the difference is easy to miss:

  - `cert-manager` does one-way TLS. The server presents a certificate,
    the connection is encrypted and the client can verify the server. The client
    is not authenticated. So this succeeds with no client certificate:


      $ curl --cacert ca.crt https://localhost:8443/hello/toto
      Hello toto

  - `mtls` does mutual TLS. Everything above, plus the server
    requires and verifies a client certificate. This is set by the property `server.ssl.client-auth = need`.
    The exact same command above is now rejected during the handshake:


      $ curl --cacert ca.crt https://localhost:8443/hello/toto
      curl: (56) ... tlsv13 alert certificate required

    and you must present a client certificate signed by the trusted CA:

      $ curl --cacert ca.crt --cert client.crt --key client.key https://localhost:8443/hello/toto
      Hello toto

The subtlety is that the two are indistinguishable until a client connects
without a certificate: only then does `mtls` reject it. Under the hood the
enabling differences are small but essential: `mtls` adds
`server.ssl.client-auth = need`, a client certificate and a CA hierarchy, consisting in a self-signed root with the CA
issuer signing both server and client certificates, so the server can validate the
client. 
The `cert-manager` branch instead uses a
single self-signed issuer and never asks the client for anything. 

