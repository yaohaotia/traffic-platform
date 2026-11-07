ARG JAVA_IMAGE=eclipse-temurin:21-jre
FROM ${JAVA_IMAGE}
WORKDIR /app
RUN groupadd --gid 10001 traffic && useradd --uid 10001 --gid traffic --no-create-home traffic && mkdir -p /data/evidence /app/data/job-logs && chown -R 10001:10001 /data /app
COPY --chown=10001:10001 target/traffic-platform-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8090 29999
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
