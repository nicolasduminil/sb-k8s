# From Spring Boot to K8S

The `master` branch of this project demonstrates how to deploy and run Spring 
Boot applications on K8s without having to manually crafting YAML files.

This branch takes it one step further and serves the very same `/hello/{who}` 
endpoint, but over HTTPS on port 8443, using an X.509 certificate that is 
provisioned, renewed and rotated automatically by [cert-manager](https://cert-manager.io/).

Instead of manually running `kubectl create deployment` and `kubectl expose`,
this branch describes the whole desired state as a set of YAML manifests (under
`k8s/`) and lets [Skaffold](https://skaffold.dev/) build the image and apply the
manifests in one shot.

## The manifest files

| Artifact | Kind | Purpose |
|----------|------|---------|
| `k8s/cluster-issuer.yaml` | `ClusterIssuer` | A cluster-wide, self-signed issuer (`ss-cluster-issuer`) used by cert-manager to sign the certificate. |
| `k8s/certificate.yaml` | `Certificate` | Requests a certificate (`sb-k8s`) from the issuer. cert-manager stores the result in the `sb-k8s-cert` secret and, because of the `keystores.jks` block, also generates a JKS keystore and truststore protected by the password held in `jks-password-secret`. Uses an ECDSA/P-256 key, is valid for `sb-k8s` and `localhost`, and is renewed 5 minutes before expiry. |
| `k8s/secret.yaml` | `Secret` | Holds the password (`jks-password-secret`) used both by cert-manager to create the JKS stores and by the application to open them. The password is given under `stringData` so Kubernetes base64-encodes it for us (a plaintext value under `data` would be interpreted as base64 and decode to invalid bytes). |
| `k8s/configmap.yaml` | `ConfigMap` | The externalised `application.properties`: switches the server to port `8443`, enables the Spring SSL *bundle* named `server`, and points the keystore/truststore locations at the mounted certificate. |
| `k8s/deployment.yaml` | `Deployment` | Runs the application. Mounts the `sb-k8s-cert` secret (the JKS files) at `/opt/secret`, mounts the ConfigMap at `/config`, injects the keystore password via the `PASSWORD` env var, sets `CERT_PATH=/opt/secret`, and points Spring at the mounted config with `SPRING_CONFIG_ADDITIONAL_LOCATION=/config/application.properties`. Exposes the container on `8443`. |
| `k8s/service.yaml` | `Service` | A `ClusterIP` service exposing port `8443`. |
| `skaffold.yaml` | Skaffold `Config` | Builds the image with Cloud Native Buildpacks (the Paketo `builder-jammy-base` builder, with `BP_JVM_VERSION=21`) and applies all the manifests above. |

## Prerequisites

In addition to `docker`, `minikube`, `kubectl` and a DockerHub account already 
required by the `master` branch, you need:

- [`skaffold`](https://skaffold.dev/docs/install/);
- [cert-manager](https://cert-manager.io/docs/installation/) installed in the cluster (see below).

## Building, deploying and running

### 1. Start minikube

    $ minikube start

### 2. Install cert-manager in the cluster

`cert-manager` is not part of Kubernetes and, consequently, it has to be installed
once per cluster. If it is already installed, skip this step, otherwise install
it as shown below:

    $ kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.20.2/cert-manager.yaml

Wait until the three cert-manager components are up before going further:

    $ kubectl wait --for=condition=Available --timeout=120s -n cert-manager deployment --all

    deployment.apps/cert-manager condition met
    deployment.apps/cert-manager-cainjector condition met
    deployment.apps/cert-manager-webhook condition met

### 3. Build the image and deploy everything with Skaffold

From the project root, a single command builds the image and applies all the
manifests listed in `skaffold.yaml`:

    $ skaffold run

Use `skaffold dev` instead if you want Skaffold to keep watching your sources and
redeploy on every change; `Ctrl-C` then automatically cleans up what it created.

Behind the scenes Skaffold creates, in order: 

  - the cluster issuer;
  - the X509 certificate that refers the cluster issuer;
  - the K8s config map containing the Spring Boot application properties. 
    These properties are generally provided as the `application.properties` 
    file but here we need to mount them at `/config`.
  - the K8s secret used as a password required by the `keystore` where 
    the `cert-manager` stores the private key and the associated X509 certificate; 
  - the K8s deployment;
  - the K8s service.

A couple of build details worth knowing:

  - The image is built with the Paketo `builder-jammy-base` builder and
    `BP_JVM_VERSION=21`. The Google Cloud
    buildpacks builder (`gcr.io/buildpacks/builder:v1`) is *not* used because its
    Ubuntu 18.04 base does not offer a Java 21 runtime.
  - The application container runs from `/workspace`, so Spring Boot's default
    `./config/` lookup would resolve to `/workspace/config`, not the ConfigMap
    mounted at `/config`. That is why `deployment.yaml` sets
    `SPRING_CONFIG_ADDITIONAL_LOCATION=/config/application.properties`. Without it
    the server would ignore the ConfigMap and stay on the default HTTP port 8080.

### 4. Check that the certificate has been issued

`cert-manager` needs a few seconds to sign the certificate and to write the JKS
keystore/truststore into the `sb-k8s-cert` secret:

    $ kubectl get certificate

    NAME     READY   SECRET        AGE
    sb-k8s   True    sb-k8s-cert   20s

    $ kubectl get secret sb-k8s-cert -o jsonpath='{.data}' | tr ',' '\n'

You should see `keystore.jks` and `truststore.jks` entries alongside the usual
`tls.crt`/`tls.key`. If `READY` stays `False`, describe the resource to find out
why:

    $ kubectl describe certificate sb-k8s

### 5. Check that the pod is running

    $ kubectl get pods

    NAME                      READY   STATUS    RESTARTS   AGE
    sb-k8s-6d4c9f8b7c-abcde   1/1     Running   0          30s

If needed, follow the application logs to confirm it started on the HTTPS port:

    $ kubectl logs -l app.kubernetes.io/name=sb-k8s -f

## Testing the HTTPS endpoint

The service is of type `ClusterIP`, so it is only reachable from inside the
cluster. The easiest way to test it from your machine is to port-forward it:

    $ kubectl port-forward svc/sb-k8s 8443:8443

`skaffold.yaml` also declares this forward, so you can let Skaffold manage it
instead, as it re-establishes the tunnel automatically whenever the pod is
replaced, which a manual `kubectl port-forward` does not:

    $ skaffold dev --port-forward

Note that `port-forward` is a local debugging convenience, not a way to *run* the
service. It is a single tunnel to one pod, tied to your machine. For real access
you would expose the service through a `NodePort`, a `LoadBalancer`
(`minikube tunnel`), or an `Ingress`. Keep in mind the certificate's SANs are
`sb-k8s` and `localhost`, so any other host/IP you expose it under must be added
to the certificate's `dnsNames`/`ipAddresses` for verification to pass.

Then, in another terminal, call the endpoint over HTTPS.

### The quick way (skips verification)

For a throw-away check you can pass `-k` (or `--insecure`), which tells `curl`
not to verify the server's certificate:

    $ curl -k https://localhost:8443/hello/toto
    Hello toto

Be aware of what `-k` really does: it does not disable TLS, the connection
is still encrypted and the server still presents its certificate. It only turns
off the client-side *identity* check. It is a convenience for testing, not how
you would call a service in production.

### The proper way (verifies the server)

`-k` is not required here and the certificate is perfectly verifiable, it is simply
signed by an issuer your machine does not trust by default. "Self-signed" means
"not in the system trust store", not "unverifiable". `cert-manager` conveniently
writes the signing certificate into the `ca.crt` entry of the `sb-k8s-cert`
secret, so you can extract it and tell `curl` to trust it:

    $ kubectl get secret sb-k8s-cert -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt
    $ curl --cacert ca.crt https://localhost:8443/hello/toto
    Hello toto

This time the server's identity is actually validated as the certificate has a
`localhost` SAN, which is why `localhost` is accepted. If you point `curl` at a
CA that did not sign the certificate or omit `--cacert` so it falls back to
the system trust store, then the request is rejected with
`unable to get local issuer certificate`. That rejection is the proof that
verification is really happening.

### Trusting the CA machine-wide (optional)

If you would rather run `curl` or a browser with no flags at all, install
that CA into your operating system's trust store once:

    $ sudo cp ca.crt /usr/local/share/ca-certificates/sb-k8s-ca.crt
    $ sudo update-ca-certificates
    $ curl https://localhost:8443/hello/toto        # no -k, no --cacert
    Hello toto

Note that a certificate trusted with zero configuration everywhere, which is the way a
public website is, requires one issued by a *publicly trusted* CA. The `cert-manager`
ACME issuer is Let's Encrypt. Trusting this issuer by our certificate needs a real,
internet-resolvable domain name and a solvable ACME challenge. That is a
deployment-to-a-real-domain concern and is not achievable against `localhost` on
minikube. Locally, trusting our own CA, as above, is the equivalent.

## Certificate renewal and rotation

cert-manager owns the whole lifecycle of the `sb-k8s` certificate, not just its
first issuance. Two fields on `k8s/certificate.yaml` drive it: the certificate's
`duration` (how long each certificate stays valid) and `renewBefore` (how long
before expiry a replacement is issued). When that moment arrives cert-manager
signs a new certificate from `ss-cluster-issuer`, rebuilds the JKS keystore and
truststore, and overwrites the `sb-k8s-cert` secret in place — the secret name
never changes, only its contents.

Because `ss-cluster-issuer` is a **self-signed** issuer, there is no separate,
long-lived CA: each certificate is its own root, so `ca.crt` in the secret is the
leaf itself and is re-generated on every renewal. That is enough to demonstrate
*that* rotation happens, but it also means a client cannot keep trusting one stable
CA across rotations — see the note at the end of this section.

### Watching it happen

cert-manager records the schedule on the `Certificate` object itself:

    $ kubectl get certificate sb-k8s \
        -o jsonpath='{.status.notAfter}{"\n"}{.status.renewalTime}{"\n"}'

`notAfter` is the expiry; `renewalTime` is when cert-manager plans to rotate (five
minutes earlier). The serial number carried inside the issued certificate is the
cleanest rotation fingerprint — it changes on every renewal:

    $ kubectl get secret sb-k8s-cert -o jsonpath='{.data.tls\.crt}' \
        | base64 -d | openssl x509 -noout -serial -dates

With the 90-day default nothing visibly rotates during a test session. To watch a
full cycle on a human timescale, shorten the lifetime temporarily (cert-manager
enforces a one-hour minimum, and `renewBefore` must be smaller than `duration`):

    spec:
      duration: 1h
      renewBefore: 5m       # renew five minutes before the hour is up

Reapply the manifest so cert-manager adopts the new lifetime — it then re-issues
straight away and keeps rotating on the shortened schedule:

    $ kubectl apply -f k8s/certificate.yaml

Because this branch's issuer is self-signed, the walkthrough stops here: `ca.crt`
rotates together with the leaf, so a client cannot keep validating against a single
pinned CA across renewals, and the application cannot reload rotated certificates
in a way that stays trusted. Getting a running application to pick up rotated
certificates in place — and keep clients connected across a rotation — relies on a
**stable CA** that outlives the leaf certificates. That PKI (`selfsigned-issuer` →
CA certificate → `ca-issuer`), together with the `reload-on-update` walkthrough
that depends on it, lives on the `mtls` branch; see that branch's README.

## Cleaning up

If you deployed with `skaffold run`, remove everything it created with:

    $ skaffold delete

`cert-manager` itself and the `minikube` cluster can be left in place for the
next run, or removed with `kubectl delete -f <the cert-manager manifest URL>` and
`minikube delete`.

