#!/usr/bin/env bash
#
# Redeploy the application, optionally switching branch first
# (e.g. cert-manager <-> mtls).
#
# minikube and cert-manager are cluster-wide and stay up the whole time -
# this script only tears down and re-creates the application's own resources.
#
# Usage:
#   ./redeploy.sh              # redeploy the CURRENT branch
#   ./redeploy.sh mtls         # switch to 'mtls', then redeploy
#   ./redeploy.sh cert-manager # switch to 'cert-manager', then redeploy
#
set -euo pipefail

TARGET="${1:-}"

echo "==> Tearing down the currently deployed resources (skaffold delete)"
skaffold delete || true

if [[ -n "$TARGET" ]]; then
  echo "==> Switching to branch '$TARGET'"
  git checkout "$TARGET"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
# The 'reset' profile deletes the cert-manager-generated secrets before deploying
# (they are not garbage-collected and differ between branches), so the new branch
# gets freshly issued certificates.
echo "==> Building and deploying branch '$BRANCH' (skaffold run -p reset)"
skaffold run -p reset

echo "==> Extracting the CA certificate to ca.crt"
kubectl get secret sb-k8s-cert -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt

echo
echo "Deployment ready on branch '$BRANCH'."
echo "In another terminal, open a port-forward (leave it running):"
echo "    kubectl port-forward svc/sb-k8s 8443:8443"
echo

if [[ "$BRANCH" == "mtls" ]]; then
  echo "==> mTLS branch: extracting the client certificate for testing"
  kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.tls\.crt}' | base64 -d > client.crt
  kubectl get secret sb-k8s-client-cert -o jsonpath='{.data.tls\.key}' | base64 -d > client.key
  echo "Test WITH a client certificate (succeeds):"
  echo "    curl --cacert ca.crt --cert client.crt --key client.key https://localhost:8443/hello/toto"
  echo "Test WITHOUT one (rejected - proves mTLS is enforced):"
  echo "    curl --cacert ca.crt https://localhost:8443/hello/toto"
else
  echo "Test (succeeds - no client certificate required):"
  echo "    curl --cacert ca.crt https://localhost:8443/hello/toto"
fi
