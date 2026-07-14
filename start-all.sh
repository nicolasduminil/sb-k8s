minikube start
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.20.2/cert-manager.yaml
kubectl wait --for=condition=Available --timeout=120s -n cert-manager deployment --all
skaffold run
kubectl get secret sb-k8s-cert       -o jsonpath='{.data.ca\.crt}'  | base64 -d > ca.crt
kubectl get secret sb-k8s-admin-cert -o jsonpath='{.data.tls\.crt}' | base64 -d > admin.crt
kubectl get secret sb-k8s-admin-cert -o jsonpath='{.data.tls\.key}' | base64 -d > admin.key
kubectl get secret sb-k8s-user-cert  -o jsonpath='{.data.tls\.crt}' | base64 -d > user.crt
kubectl get secret sb-k8s-user-cert  -o jsonpath='{.data.tls\.key}' | base64 -d > user.key
kubectl port-forward svc/sb-k8s 8443:8443 &
