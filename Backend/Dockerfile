FROM maven:3.9.6-eclipse-temurin-8

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

EXPOSE 8080

CMD ["mvn", "spring-boot:run"]