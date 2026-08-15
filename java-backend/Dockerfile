# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# BuildKit keeps Maven dependencies in a reusable cache instead of throwing
# them away with every temporary builder container. A configured Maven mirror
# can be supplied through MAVEN_MIRROR_URL when Maven Central is unavailable.
ARG MAVEN_MIRROR_URL=
COPY pom.xml ./
RUN --mount=type=cache,id=interview-guide-maven,target=/root/.m2 \
    if [ -n "$MAVEN_MIRROR_URL" ]; then \
      printf '%s\n' '<settings><mirrors><mirror><id>project-mirror</id><mirrorOf>*</mirrorOf><url>'"$MAVEN_MIRROR_URL"'</url></mirror></mirrors></settings>' > /tmp/settings.xml; \
      mvn -B -q -s /tmp/settings.xml -DskipTests dependency:go-offline; \
    else \
      mvn -B -q -DskipTests dependency:go-offline; \
    fi

COPY src ./src
RUN --mount=type=cache,id=interview-guide-maven,target=/root/.m2 \
    if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mvn -B -q -s /tmp/settings.xml -DskipTests package; \
    else \
      mvn -B -q -DskipTests package; \
    fi

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/interview-guide-backend-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
