FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY clawkit-tools/pom.xml clawkit-tools/pom.xml
COPY clawkit-provider/pom.xml clawkit-provider/pom.xml
COPY clawkit-context/pom.xml clawkit-context/pom.xml
COPY clawkit-memory/pom.xml clawkit-memory/pom.xml
COPY clawkit-observability/pom.xml clawkit-observability/pom.xml
COPY clawkit-engine/pom.xml clawkit-engine/pom.xml
COPY clawkit-im/pom.xml clawkit-im/pom.xml
COPY clawkit-cli/pom.xml clawkit-cli/pom.xml
RUN mvn -B -ntp -pl clawkit-cli -am dependency:go-offline
COPY clawkit-tools clawkit-tools
COPY clawkit-provider clawkit-provider
COPY clawkit-context clawkit-context
COPY clawkit-memory clawkit-memory
COPY clawkit-observability clawkit-observability
COPY clawkit-engine clawkit-engine
COPY clawkit-im clawkit-im
COPY clawkit-cli clawkit-cli
RUN mvn -B -ntp -pl clawkit-cli -am package -DskipTests
RUN find clawkit-cli/target -maxdepth 1 -name 'clawkit-cli-*.jar' ! -name 'original-*' \
    -exec cp '{}' /tmp/clawkit.jar \;

FROM maven:3.9.9-eclipse-temurin-21
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 clawkit
WORKDIR /workspace
COPY --from=build /tmp/clawkit.jar /opt/clawkit/clawkit.jar
RUN chown -R clawkit:clawkit /opt/clawkit /workspace /home/clawkit
USER clawkit
VOLUME ["/workspace", "/home/clawkit/.clawkit"]
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/opt/clawkit/clawkit.jar"]
