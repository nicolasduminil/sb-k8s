minikube start
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.20.2/cert-manager.yaml
kubectl wait --for=condition=Available --timeout=120s -n cert-manager deployment --all
skaffold run
kubectl get secret sb-k8s-cert          -o jsonpath='{.data.ca\.crt}'  | base64 -d > ca.crt
kubectl get secret sb-k8s-admin-cert    -o jsonpath='{.data.tls\.crt}' | base64 -d > admin.crt
kubectl get secret sb-k8s-admin-cert    -o jsonpath='{.data.tls\.key}' | base64 -d > admin.key
kubectl get secret sb-k8s-user-cert     -o jsonpath='{.data.tls\.crt}' | base64 -d > user.crt
kubectl get secret sb-k8s-user-cert     -o jsonpath='{.data.tls\.key}' | base64 -d > user.key
kubectl get secret sb-k8s-intruder-cert -o jsonpath='{.data.tls\.crt}' | base64 -d > intruder.crt
kubectl get secret sb-k8s-intruder-cert -o jsonpath='{.data.tls\.key}' | base64 -d > intruder.key
# PKCS12 keystores + truststore for the e2e test (SecurityE2eIT).
for id in admin user intruder; do
  openssl pkcs12 -export -inkey "$id.key" -in "$id.crt" -certfile ca.crt \
    -name "$id" -passout pass:changeit -out "$id.p12"
done
rm -f truststore.p12
keytool -importcert -noprompt -alias ca -file ca.crt \
  -storetype PKCS12 -keystore truststore.p12 -storepass changeit
kubectl port-forward svc/sb-k8s 8443:8443 &
