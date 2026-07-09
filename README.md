# From Spring Boot to K8S

> This section documents the `mtls` branch, which builds on the
> `cert-manager` branch above. Everything already described (Skaffold, buildpacks,
> the JKS keystore, the ConfigMap, `SPRING_CONFIG_ADDITIONAL_LOCATION`, …) still
> applies; only the differences are listed here.
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


> **Note** — at this stage authentication stops at the transport layer: the
> server accepts or refuses the handshake but the application itself does not yet
> know *who* the caller is. Mapping the client certificate to an authenticated
> principal (and authorizing on it) with Spring Security X.509 is the subject of a
> later branch.

## Switching branches and redeploying

This repository has more than one branch: `cert-manager`, `mtls`, …. Switching
between them is a *cluster* operation. `minikube` and `cert-manager` stay up the
whole time, only the application's own resources are recreated. In particular 
there is no need to stop/restart minikube or to build by hand since `skaffold run`
does it in one step.

The `redeploy.sh` helper automates the full sequence:

  - tear down:
  - optionally switch branch;
  - rebuild and redeploy;
  - re-extract `ca.crt` and, on the `mtls` branch, the client certificate.


    $ ./redeploy.sh              # redeploy the current branch
    $ ./redeploy.sh mtls         # switch to mtls, then redeploy
    $ ./redeploy.sh cert-manager # switch to cert-manager, then redeploy

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

