FROM ubuntu:20.04

RUN apt-get update -y

ADD "https://cdn.azul.com/zulu/bin/zulu17.42.21-ca-crac-jdk17.0.7-linux_x64.tar.gz" /opt/
RUN cd /opt/ && tar -xzf zulu17.42.21-ca-crac-jdk17.0.7-linux_x64.tar.gz && rm zulu17.42.21-ca-crac-jdk17.0.7-linux_x64.tar.gz

ENV JAVA_HOME /opt/zulu17.42.21-ca-crac-jdk17.0.7-linux_x64
ENV PATH $JAVA_HOME/bin:$PATH

RUN mkdir -p /opt/crac-files
COPY target/jpa-crac-demo-0.0.1-SNAPSHOT.jar /opt/app/jpa-crac-demo-0.0.1-SNAPSHOT.jar

#FROM my_jpa_app_on_crac
#COPY crac-files /opt/crac-files


#docker build -t my_jpa_app_on_crac .

#docker run -it --privileged --rm --name my_jpa_app_on_crac \
#    -v $PWD/crac-files:/opt/crac-files my_jpa_app_on_crac \
#    bash -c "echo 128 > /proc/sys/kernel/ns_last_pid;
#    java -XX:CRaCCheckpointTo=/opt/crac-files -jar /opt/app/jpa-crac-demo-0.0.1-SNAPSHOT.jar"

#docker exec my_app_on_crac jcmd PID-OR-NAME JDK.checkpoint
