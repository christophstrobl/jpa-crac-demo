Requires MySQL Database:

```bash
docker run -d \                                                                                                                                                     125 ↵
    -p 3306:3306 \
    -e MYSQL_ROOT_PASSWORD=sa \
    arm64v8/mysql:latest
```

```bash
docker exec -it mysql mysql -p
mysql> create database test;
```

Build the app

```bash
mvn clean package
docker build -t my_jpa_app_on_crac .

docker run -it --privileged --rm --name my_jpa_app_on_crac \
    -v $PWD/crac-files:/opt/crac-files my_jpa_app_on_crac \
    bash -c "echo 128 > /proc/sys/kernel/ns_last_pid;
    java --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -XX:CRaCCheckpointTo=/opt/crac-files -XX:CRaCCheckpointTo=/opt/crac-files -jar /opt/app/jpa-crac-demo-0.0.1-SNAPSHOT.jar"
    
docker exec my_jpa_app_on_crac jcmd 129 JDK.checkpoint    
```

Build the image
```bash
docker build -t my_jpa_app_on_crac_restore .
docker run -it --rm my_jpa_app_on_crac_restore java \
    -XX:CRaCRestoreFrom=/opt/crac-files
```

