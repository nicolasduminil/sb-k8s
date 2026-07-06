# From Spring Boot to K8S

This project demonstrates how to deploy and run Spring Boot applications on K8S.
It requires `docker` and `minikube` installed locally, as well as having a 
docker hub account.

The typical way to deploy and run applications on K8S is to provide YAML manifest
files defining the kind of K8S controllers to be used, for example pods, deployments,
services, etc. It's quite common that, in order to deploy and run a simple Spring
Boot application that does "hello world", one needs a half dozen of YAML manifest
files. And the fact that these files could be concatenated in a single one doesn't
change much to the fact that, at the end of the day, almost 100 YAML lines are 
required for such a simple operation.

The good news is that, using Cloud Native Buildpacks, you don't need to provide
anymore all these YAML files and to bother with K8S details, because the `spring
-boot-maven-plugin` is able to generate the Docker image of the application, by
simply leveraging its `build-image` goal.

Here is the plugin configuration:

      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <image>
            <name>nicolasduminil/k8s-sb:buildpacks</name>
            <env>
              <BP_JVM_VERSION>25</BP_JVM_VERSION>
            </env>
            <publish>true</publish>
          </image>
        </configuration>
      </plugin>

The `name` element above overrides the Docker image name that will be generated
while the `env` element defines the JVM version. The generated image is published
on DockerHub registry. By default, Spring Boot uses Packeto Bellsoft Liberica 
Java Buildpack, which is a Cloud Native Buildpacks provider. If you prefer to 
use another one, you need then to override the buildpacks list in the Maven plugin
configuration, for example:

      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <image>
            <name>nicolasduminil/k8s-sb:buildpacks</name>
            <buildpack>
              <buildpack>gcr.io/packeto-buildpacks/adoptium:latest<buldpack>
              <buildpack>urn:cnb:builder:paketo-buildpacks/java</buildpack>
            </env>
            <publish>true</publish>
          </image>
        </configuration>
      </plugin>

The configuration above allows to use Eclipse Temurin instead of Bellsoft 
Liberica OpenJDK.

## Building, deploying and running

In order to build the application execute the following command:

    $ mvn clean package spring-boot:build-image

This will create the application JAR and the Docker image associated to it.
You should see something like:

    INFO] Successfully built image 'docker.io/nicolasduminil/k8s-sb:buildpacks'
    [INFO]
    [INFO]  > Pushing image 'docker.io/nicolasduminil/k8s-sb:buildpacks' 11%
    [INFO]  > Pushing image 'docker.io/nicolasduminil/k8s-sb:buildpacks' 15%
    [INFO]  > Pushing image 'docker.io/nicolasduminil/k8s-sb:buildpacks' 15%
    [INFO]  > Pushing image 'docker.io/nicolasduminil/k8s-sb:buildpacks' 100%
    [INFO]  > Pushed image 'docker.io/nicolasduminil/k8s-sb:buildpacks'

Now, start `minikube` if not already done:

    $ minikube start
    😄  minikube v1.32.0 on Ubuntu 24.04  
    ✨  Using the docker driver based on user configuration
    📌  Using Docker driver with root privileges
    👍  Starting control plane node minikube in cluster minikube
    🚜  Pulling base image ...
    🔥  Creating docker container (CPUs=2, Memory=7900MB) ...
    🐳  Preparing Kubernetes v1.28.3 on Docker 24.0.7 ...
    ▪ Generating certificates and keys ...
    ▪ Booting up control plane ...
    ▪ Configuring RBAC rules ...
    🔗  Configuring bridge CNI (Container Networking Interface) ...
    ▪ Using image gcr.io/k8s-minikube/storage-provisioner:v5
    🔎  Verifying Kubernetes components...
    🌟  Enabled addons: storage-provisioner, default-storageclass

    ❗  /usr/local/bin/kubectl is version 1.34.3, which may have incompatibilities with Kubernetes 1.28.3.
    ▪ Want kubectl v1.28.3? Try 'minikube kubectl -- get pods -A'
    🏄  Done! kubectl is now configured to use "minikube" cluster and "default" namespace by default

Next, you need to create a deployment:

    $ kubectl create deployment k8s-sb --image nicolasduminil/k8s-sb:buildpacks

and to expose the associated service:

    $ kubectl expose deployment k8s-sb --type=NodePort --port=8080

In order to invoke the REST endpoint, you need to have its public URL. The 
following command will show it to you:

    $ minikube service list
    |-------------|------------|--------------|---------------------------|
    |  NAMESPACE  |    NAME    | TARGET PORT  |            URL            |
    |-------------|------------|--------------|---------------------------|
    | default     | k8s-sb     |         8080 | http://192.168.49.2:30809 |
    | default     | kubernetes | No node port |                           |
    | kube-system | kube-dns   | No node port |                           |
    |-------------|------------|--------------|---------------------------|

All you need to do now is to send a HTTP GET request to the endpoint, as follows:

    curl http://192.168.49.2:30809/hello/toto
    Hello toto

As you can see, as a proof a goodwill the endpoint greets you.

---

# The `cert-manager` branch: running the application over TLS

The `master` branch above exposes the application over plain HTTP on port 8080.
The `cert-manager` branch takes it one step further and serves the very same
`/hello/{who}` endpoint over **HTTPS on port 8443**, using an X.509 certificate
that is provisioned, renewed and rotated automatically by
[cert-manager](https://cert-manager.io/).

Instead of manually running `kubectl create deployment` and `kubectl expose`,
this branch describes the whole desired state as a set of YAML manifests (under
`k8s/`) and lets [Skaffold](https://skaffold.dev/) build the image and apply the
manifests in one shot.

## What changed compared to `master`

| Artifact | Kind | Purpose |
|----------|------|---------|
| `k8s/cluster-issuer.yaml` | `ClusterIssuer` | A cluster-wide, **self-signed** issuer (`ss-cluster-issuer`) used by cert-manager to sign the certificate. |
| `k8s/certificate.yaml` | `Certificate` | Requests a certificate (`sb-k8s`) from the issuer. cert-manager stores the result in the `sb-k8s-cert` secret and, because of the `keystores.jks` block, also generates a **JKS keystore and truststore** protected by the password held in `jks-password-secret`. Uses an ECDSA/P-256 key, is valid for `sb-k8s` and `localhost`, and is renewed 5 minutes before expiry. |
| `k8s/secret.yaml` | `Secret` | Holds the password (`jks-password-secret`) used both by cert-manager to create the JKS stores and by the application to open them. The password is given under `stringData` so Kubernetes base64-encodes it for us (a plaintext value under `data` would be interpreted as base64 and decode to invalid bytes). |
| `k8s/configmap.yaml` | `ConfigMap` | The externalised `application.properties`: switches the server to port `8443`, enables the Spring SSL *bundle* named `server`, and points the keystore/truststore locations at the mounted certificate. |
| `k8s/deployment.yaml` | `Deployment` | Runs the application. Mounts the `sb-k8s-cert` secret (the JKS files) at `/opt/secret`, mounts the ConfigMap at `/config`, injects the keystore password via the `PASSWORD` env var, sets `CERT_PATH=/opt/secret`, and points Spring at the mounted config with `SPRING_CONFIG_ADDITIONAL_LOCATION=/config/application.properties`. Exposes the container on `8443`. |
| `k8s/service.yaml` | `Service` | A `ClusterIP` service exposing port `8443`. |
| `k8s/deployment-ss.yaml` | `Deployment` | An **alternative** deployment that sources its configuration from HashiCorp Vault through the [Secrets Store CSI driver](https://secrets-store-csi-driver.sigs.k8s.io/) instead of a ConfigMap/Secret pair (see the last section). |
| `skaffold.yaml` | Skaffold `Config` | Builds the image with Cloud Native Buildpacks (the Paketo `builder-jammy-base` builder, with `BP_JVM_VERSION=21`) and applies all the manifests above. |

## Prerequisites

In addition to `docker`, `minikube` and a DockerHub account already required by
the `master` branch, you need:

- [`kubectl`](https://kubernetes.io/docs/tasks/tools/) (bundled with `minikube`);
- [`skaffold`](https://skaffold.dev/docs/install/);
- [cert-manager](https://cert-manager.io/docs/installation/) installed **in the
  cluster** (see below).

## Building, deploying and running

### 1. Start minikube

    $ minikube start

### 2. Install cert-manager in the cluster

cert-manager is not part of Kubernetes; it has to be installed once per cluster.
If it is already installed, skip this step.

**Recommended — with Helm.** This is the way used to develop this branch. It
installs the CRDs together with the chart and makes upgrades/uninstalls clean:

    $ helm install cert-manager \
        oci://quay.io/jetstack/charts/cert-manager \
        --version v1.20.2 \
        --namespace cert-manager \
        --create-namespace \
        --set crds.enabled=true

(Equivalently, from the Jetstack chart repository, after
`helm repo add jetstack https://charts.jetstack.io && helm repo update`:
`helm install cert-manager jetstack/cert-manager --namespace cert-manager
--create-namespace --set crds.enabled=true`.)

**Alternative — with `kubectl`.** You can instead apply the official static
manifest (use the latest release tag):

    $ kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.20.2/cert-manager.yaml

> **Note** — do not mix the two methods. If cert-manager was installed with
> Helm, running `kubectl apply` on the static manifest prints warnings such as
> *"resource ... is missing the kubectl.kubernetes.io/last-applied-configuration
> annotation ..."*. These are harmless (kubectl patches the annotation and
> reports the resources as `configured`), but overlaying a *different* version on
> top of an existing install can leave CRDs and webhooks in a mixed state. Pick
> one method and, if you need to switch, uninstall the previous one first
> (`helm uninstall cert-manager -n cert-manager`). You can check what is already
> present with `helm list -n cert-manager` and `kubectl get pods -n cert-manager`.

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

Behind the scenes Skaffold creates, in order: the `ClusterIssuer`, the
`Certificate`, the `ConfigMap`, the `Secret`, the `Deployment` and the `Service`.

A couple of build details worth knowing:

- The image is built with the **Paketo `builder-jammy-base`** builder and
  `BP_JVM_VERSION=21` (this project targets Java 21). The Google Cloud
  buildpacks builder (`gcr.io/buildpacks/builder:v1`) is *not* used because its
  Ubuntu 18.04 base does not offer a Java 21 runtime.
- The application container runs from `/workspace`, so Spring Boot's default
  `./config/` lookup would resolve to `/workspace/config`, **not** the ConfigMap
  mounted at `/config`. That is why `deployment.yaml` sets
  `SPRING_CONFIG_ADDITIONAL_LOCATION=/config/application.properties`; without it
  the server would ignore the ConfigMap and stay on the default HTTP port 8080.

### 4. Check that the certificate has been issued

cert-manager needs a few seconds to sign the certificate and to write the JKS
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
instead — it re-establishes the tunnel automatically whenever the pod is
replaced (which a manual `kubectl port-forward` does not):

    $ skaffold dev --port-forward

Note that `port-forward` is a local debugging convenience, not a way to *run* the
service — it is a single tunnel to one pod, tied to your machine. For real access
you would expose the service through a `NodePort`, a `LoadBalancer`
(`minikube tunnel`), or an `Ingress`; keep in mind the certificate's SANs are
`sb-k8s` and `localhost`, so any other host/IP you expose it under must be added
to the certificate's `dnsNames`/`ipAddresses` for verification to pass.

Then, in another terminal, call the endpoint over HTTPS. **On this branch the
server requires a client certificate** (`server.ssl.client-auth = need`), so a
plain `curl -k` — or even `curl --cacert ca.crt` — with no client certificate is
rejected during the handshake. The complete testing flow (quick vs verified,
trusting the CA machine-wide, and the with/without-client-certificate contrast)
is covered in [Testing mTLS](#testing-mtls) below.

## Cleaning up

If you deployed with `skaffold run`, remove everything it created with:

    $ skaffold delete

cert-manager itself (and the `minikube` cluster) can be left in place for the
next run, or removed with `kubectl delete -f <the cert-manager manifest URL>` and
`minikube delete`.

## Switching branches and redeploying (`redeploy.sh`)

This repository has more than one branch (`cert-manager`, `mtls`, …). Switching
between them is a *cluster* operation, not a minikube one: **minikube and
cert-manager stay up the whole time** — only the application's own resources are
recreated. In particular there is no need to stop/restart minikube or to build
by hand (`skaffold run` builds and deploys in one step).

The `redeploy.sh` helper automates the full sequence — tear down, optionally
switch branch, rebuild and redeploy, then re-extract `ca.crt` (and, on the
`mtls` branch, the client certificate) ready for testing:

    $ ./redeploy.sh              # redeploy the current branch
    $ ./redeploy.sh mtls         # switch to mtls, then redeploy
    $ ./redeploy.sh cert-manager # switch to cert-manager, then redeploy

Under the hood it runs `skaffold delete`, `git checkout <branch>`, then
`skaffold run -p reset`. The **`reset` profile** (defined in `skaffold.yaml`)
deletes the cert-manager-generated secrets *before* deploying, so the new branch
gets freshly issued certificates. This matters when switching branches because
those secrets are not garbage-collected on their own and their contents differ
between branches; it is kept in a profile so a plain `skaffold dev` / `skaffold
run` does not needlessly re-issue certificates on every cycle.

Two things the script takes care of that are easy to forget by hand: the
`ca.crt` **changes** between branches (different issuer), so it must be
re-extracted, and a manual `kubectl port-forward` dies with the old pod and has
to be restarted (using `skaffold dev --port-forward` avoids that entirely).

## `cert-manager` vs `mtls`: the subtle difference

The `cert-manager` and `mtls` branches look almost identical — same cert-manager,
same JKS keystore, same Skaffold flow, HTTPS on 8443 — yet they answer two
different questions, and the difference is easy to miss:

- **`cert-manager` — one-way (server) TLS.** The server presents a certificate;
  the connection is encrypted and the *client can verify the server*. The client
  is **not** authenticated. So this succeeds with no client certificate:

      $ curl --cacert ca.crt https://localhost:8443/hello/toto
      Hello toto

- **`mtls` (this branch) — mutual TLS.** Everything above, **plus** the server
  *requires and verifies a client certificate* (`server.ssl.client-auth = need`).
  The exact same command above is now **rejected** during the handshake:

      $ curl --cacert ca.crt https://localhost:8443/hello/toto
      curl: (56) ... tlsv13 alert certificate required

  and you must present a client certificate signed by the trusted CA:

      $ curl --cacert ca.crt --cert client.crt --key client.key https://localhost:8443/hello/toto
      Hello toto

The subtlety is that the two are indistinguishable *until a client connects
without a certificate*: only then does `mtls` reject it. Under the hood the
enabling differences are small but essential — `mtls` adds
`server.ssl.client-auth = need`, a **CA hierarchy** (a self-signed root → CA
issuer signing *both* server and client certs, so the server can validate the
client), and a **client certificate**. The `cert-manager` branch instead uses a
single self-signed issuer and never asks the client for anything. The full setup
is described in [Mutual TLS (mTLS)](#mutual-tls-mtls) below.

## Alternative: sourcing the configuration from Vault (`deployment-ss.yaml`)

`k8s/deployment-ss.yaml` demonstrates a variant where the application is not fed
by a ConfigMap/Secret but by [HashiCorp Vault](https://www.vaultproject.io/)
through the [Secrets Store CSI driver](https://secrets-store-csi-driver.sigs.k8s.io/).
Spring Boot is pointed at the mounted file via
`SPRING_CONFIG_ADDITIONAL_LOCATION=/mnt/secrets-store/application.properties`.

This path requires extra components in the cluster (the Secrets Store CSI driver,
the Vault provider and a `SecretProviderClass` named `vault-database`) and is
therefore **not** part of the default `skaffold run` flow described above (only
`deployment.yaml` is listed in `skaffold.yaml`). Both manifests define a
`Deployment` named `sb-k8s`, so they are mutually exclusive: deploy this variant
on its own, once those prerequisites are in place, with:

    $ kubectl apply -f k8s/deployment-ss.yaml

---

# Mutual TLS (mTLS)

> This section documents the **`mtls` branch**, which builds on the
> `cert-manager` branch above. Everything already described (Skaffold, buildpacks,
> the JKS keystore, the ConfigMap, `SPRING_CONFIG_ADDITIONAL_LOCATION`, …) still
> applies; only the differences are listed here.

The `cert-manager` branch uses **one-way TLS**: the server presents a
certificate, the connection is encrypted, but *anyone* who can reach the socket
gets served. The `mtls` branch turns on **mutual TLS**, so the server also
requires the *client* to present a certificate signed by a trusted CA. A caller
without a valid client certificate is rejected during the TLS handshake — this is
authentication at the transport layer.

## Why a CA hierarchy is required

Plain mutual TLS cannot work with a single *self-signed* issuer: every
certificate would be its own, unrelated root, so the server would have no way to
validate a client certificate. This branch therefore introduces a tiny in-cluster
PKI (in `k8s/issuers.yaml`):

1. a **self-signed `Issuer`** (`selfsigned-issuer`) — used only to bootstrap the CA;
2. a **CA certificate** (`sb-k8s-ca`, `isCA: true`) — the shared root of trust;
3. a **CA `Issuer`** (`ca-issuer`) that signs **both** the server and the client
   certificates.

Because both certificates share the same CA, the server's truststore (which
cert-manager fills with that CA) validates the client certificate, and vice
versa. Namespaced `Issuer`s are used rather than `ClusterIssuer`s so the CA secret
stays in the `default` namespace (a CA `ClusterIssuer` would expect it in
cert-manager's cluster-resource namespace instead).

## What changed compared to the `cert-manager` branch

| Artifact | Change |
|----------|--------|
| `k8s/issuers.yaml` | **New** — replaces `cluster-issuer.yaml`. Holds the self-signed `Issuer`, the CA `Certificate`, and the CA `Issuer` described above. |
| `k8s/certificate.yaml` | The server certificate is now signed by `ca-issuer` (was the self-signed issuer) and declares `usages: [server auth, …]`. |
| `k8s/client-certificate.yaml` | **New** — a client certificate (`sb-k8s-client`, `usages: [client auth, …]`) signed by the same `ca-issuer`, stored as PEM in the `sb-k8s-client-cert` secret. |
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

First extract the client certificate, its key and the CA from the
`sb-k8s-client-cert` secret so `curl` can use them:

    $ kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.tls\.crt}' | base64 -d > client.crt
    $ kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.tls\.key}' | base64 -d > client.key
    $ kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.ca\.crt}'  | base64 -d > ca.crt

Port-forward the service (leave it running in its own terminal):

    $ kubectl port-forward svc/sb-k8s 8443:8443

### The quick way (skips server verification)

You can pass `-k` (or `--insecure`) to skip verifying the *server's* certificate.
On this branch you still have to send the *client* certificate — `-k` only turns
off the client-side identity check of the server, it does **not** disable TLS or
waive the server's `client-auth = need` requirement:

    $ curl -k --cert client.crt --key client.key https://localhost:8443/hello/toto
    Hello toto

### The proper way (verifies the server too)

`-k` is not required — the server certificate is verifiable, it is just signed by
a CA (`sb-k8s-ca`) your machine does not trust by default. cert-manager writes
that CA into the `ca.crt` extracted above, so pass it with `--cacert` to validate
the server for real (the certificate has a `localhost` SAN, which is why
`localhost` is accepted):

    $ curl --cacert ca.crt --cert client.crt --key client.key https://localhost:8443/hello/toto
    Hello toto

If you point `curl` at a CA that did *not* sign the server certificate — or omit
`--cacert` so it falls back to the system trust store — the request fails with
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

A certificate trusted with **zero configuration everywhere** (like a public
website) would require one from a *publicly trusted* CA — e.g. via cert-manager's
ACME (Let's Encrypt) issuer — which needs a real, internet-resolvable domain and
a solvable ACME challenge, not achievable against `localhost` on minikube.

> **Note** — at this stage authentication stops at the transport layer: the
> server accepts or refuses the handshake but the application itself does not yet
> know *who* the caller is. Mapping the client certificate to an authenticated
> principal (and authorizing on it) with Spring Security X.509 is the subject of a
> later branch.