FROM maven:3.9.9-eclipse-temurin-21
WORKDIR /app
COPY target/order-api.jar /app/order-api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/order-api.jar"]
