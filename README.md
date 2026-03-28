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