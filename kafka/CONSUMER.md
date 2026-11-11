# Kafka Consumer - analytics-service

How **`analytics-service`** reads the `PatientEvent` messages that `patient-service`
publishes to the Kafka topic **`patient`**.

- Kafka itself (the broker container): [README.md](README.md)
- The producer side (patient-service): [PRODUCER.md](PRODUCER.md)

Contents:
- [What happens](#what-happens)
- [Files involved](#files-involved)
- [1. Dependencies and build](#1-dependencies-and-build)
- [2. The event contract (`.proto`)](#2-the-event-contract-proto)
- [3. Configuration](#3-configuration)
- [4. The consumer class](#4-the-consumer-class)
- [5. Docker](#5-docker)
- [Run and verify](#run-and-verify)
- [Troubleshooting](#troubleshooting)
- [Improvements before production](#improvements-before-production)

---

## What happens

```
patient-service ──PatientEvent (bytes)──► Kafka topic "patient" ──► analytics-service
   (producer)                                                       @KafkaListener
                                                                    group "analytics-service"
                                                                    PatientEvent.parseFrom(bytes)
```

- patient-service doesn't know analytics-service exists - it only publishes to the topic.
- analytics-service subscribes as consumer group **`analytics-service`**. Kafka remembers
  the group's position (offset), so after a restart it continues where it stopped.
- More consumer services (notifications, audit, ...) can read the same events by using
  **their own** `groupId` - each group gets every event.

---

## Files involved

| File | Purpose |
|---|---|
| `analytics-service/pom.xml` | Kafka starter, protobuf runtime, protobuf code generation |
| `analytics-service/src/main/proto/patient_event.proto` | Copy of the event contract (must match the producer) |
| `analytics-service/src/main/java/com/pm/analyticsservice/kafka/KafkaConsumer.java` | `@KafkaListener` that decodes and handles events |
| `analytics-service/src/main/resources/application.properties` | Consumer deserializers |
| `analytics-service/Dockerfile` | Multi-stage build of the service image |
| IntelliJ run config `analytics-service` | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, network `internal` |

---

## 1. Dependencies and build

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-kafka</artifactId>
</dependency>

<!-- No version: Spring Boot manages protobuf-java (same as patient-service) -->
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
</dependency>
```

- `spring-boot-starter-kafka` - in Spring Boot 4 the starter is required for
  auto-configuration (`spring-kafka` alone isn't enough).
- `protobuf-java` - runtime for the generated `PatientEvent` class. No version: Spring Boot
  4.1.2 manages it (4.36.2), so it matches patient-service.

Build section - generates `PatientEvent` from `src/main/proto`:

```xml
<build>
    <extensions>
        <!-- Sets ${os.detected.classifier} (e.g. osx-aarch_64) so the right protoc is downloaded -->
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>1.7.0</version>
        </extension>
    </extensions>
    <plugins>
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>
            <version>0.6.1</version>
            <configuration>
                <protocArtifact>com.google.protobuf:protoc:${protobuf-java.version}:exe:${os.detected.classifier}</protocArtifact>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>   <!-- messages only -->
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Unlike patient-service and billing-service, there is **no gRPC** here: no
`protoc-gen-grpc-java` plugin and no `compile-custom` goal. analytics-service only needs
the message class, not gRPC stubs.

---

## 2. The event contract (`.proto`)

`analytics-service/src/main/proto/patient_event.proto` - a **copy** of the producer's file:

```protobuf
syntax="proto3";

package patient.events;
option java_multiple_files = true;

message PatientEvent {
  string patientId = 1;
  string name = 2;
  string email = 3;
  string event_type = 4;
}
```

What must match the producer exactly:
- **Field numbers and types** - they are what's actually in the bytes.
- **`package`** - decides the Java package (`patient.events`), so both sides use the
  same class name.

What doesn't have to match: the file name (`patient_event.proto` is the protobuf
convention: `lower_snake_case`).

If the producer adds a field (`= 5`), this consumer keeps working - protobuf ignores
unknown fields. It only sees the new field after its copy of the `.proto` is updated.

---

## 3. Configuration

`analytics-service/src/main/resources/application.properties`

```properties
spring.application.name=analytics-service

spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer
```

The producer sends a `String` key and `byte[]` value, so the consumer must turn bytes
back into the same types.

| Role | Property | Class |
|---|---|---|
| **Producer** (sends) - patient-service | `spring.kafka.producer.*-serializer` | `StringSerializer`, `ByteArraySerializer` |
| **Consumer** (reads) - analytics-service | `spring.kafka.consumer.*-deserializer` | `StringDeserializer`, `ByteArrayDeserializer` |

**Kafka address** (`spring.kafka.bootstrap-servers`):

| analytics-service runs | Set |
|---|---|
| As a container on the `internal` network | env var `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |
| From IntelliJ on your Mac | `spring.kafka.bootstrap-servers=localhost:9094` |

Without it the default is `localhost:9092`, which inside a container is the container
itself - the consumer never connects.

---

## 4. The consumer class

`analytics-service/src/main/java/com/pm/analyticsservice/kafka/KafkaConsumer.java`

```java
@Service
public class KafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumer.class);

    @KafkaListener(topics = "patient", groupId = "analytics-service")
    public void consumeEvent(byte[] event){
        try {
            PatientEvent patientEvent = PatientEvent.parseFrom(event);   // bytes -> object
            // ... perform any business related to analytics here

            log.info("Received Patient Event: [PatientId={}, PatientName={}, " +
                    "PatientEmail={} ]",
                    patientEvent.getPatientId(),
                    patientEvent.getName(),
                    patientEvent.getEmail()
            );
        } catch (InvalidProtocolBufferException e) {
            log.error("Error deserializing event: {} -> {}", e.getMessage(), event);
            throw new RuntimeException(e);
        }
    }
}
```

- `@KafkaListener(topics = "patient", ...)` - Spring starts a background consumer that
  calls this method for every message on the topic.
- `groupId = "analytics-service"` - the consumer group. Instances with the same group
  **share** the partitions; a different group receives **all** events again.
- `byte[] event` - the value after `ByteArrayDeserializer`.
- `PatientEvent.parseFrom(event)` - decodes the protobuf bytes using the generated class.
- If the bytes aren't a valid `PatientEvent`, the method throws. Spring Kafka's default
  error handler then retries the message (10 attempts in total), logs it and **skips** it
  - see [improvements](#improvements-before-production).

---

## 5. Docker

`analytics-service/Dockerfile` - same multi-stage pattern as patient-service:

```dockerfile
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package

FROM eclipse-temurin:21-jre AS runner
WORKDIR /app
COPY --from=builder ./app/target/analytics-service-0.0.1-SNAPSHOT.jar ./app.jar
EXPOSE 4002
ENTRYPOINT ["java" , "-jar" , "app.jar"]
```

IntelliJ run configuration (**Docker > Dockerfile**):

| Field | Value |
|---|---|
| Dockerfile | `analytics-service/Dockerfile` |
| Image tag | `analytics-service:latest` |
| Container name | `analytics-service` |
| Run options | `--network internal` |
| Environment variables | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |
| Bind ports | `4002:4002` |

> **Port note:** `application.properties` has no `server.port`, so the app listens on
> Spring's default **8080**, not 4002 - the `4002:4002` binding and `EXPOSE 4002` point to
> nothing. It doesn't matter yet (the service has no HTTP endpoints), but add
> `server.port=4002` before adding any.

> `mvn clean package` runs the `contextLoads` test during the image build. It passes
> without a running Kafka - the listener just logs connection warnings until Kafka is
> reachable.

---

## Run and verify

Start order: `kafka` → `patient-service-db` → `billing-service` → `analytics-service` →
`patient-service`.

1. analytics-service logs at startup should show:
   ```
   bootstrap.servers = [kafka:9092]
   group.id = analytics-service
   analytics-service: partitions assigned: [patient-0]
   ```
2. Create a patient with a **new** email (`api-requests/patient-service/create-patient.http`).
3. analytics-service logs:
   ```
   Received Patient Event: [PatientId=c4313c95-..., PatientName=John Doe KAFKA Consumer, PatientEmail=... ]
   ```
4. Check the group's position and lag (lag `0` = everything consumed):
   ```bash
   docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:9092 --describe --group analytics-service
   ```

Because Kafka keeps the events, if analytics-service is **stopped** while patients are
created, it processes all of them when it starts again.

---

## Troubleshooting

| Error / symptom | Cause | Fix |
|---|---|---|
| `Could not find artifact com.google.protobuf:protoc:exe:${os.detected.classifier}` | `os-maven-plugin` extension missing - the placeholder is never filled in | Add the `<extensions>` block |
| Build fails on gRPC plugin / `protoc-gen-grpc-java` | gRPC code generation copied from another service | Remove `pluginId`, `pluginArtifact` and `compile-custom` |
| `StringSerializer is not an instance of ...Deserializer` / `Failed to construct kafka consumer` | Consumer properties pointed to `...Serializer` classes | Use `StringDeserializer` / `ByteArrayDeserializer` |
| Image build fails in `contextLoads` with the error above | The test starts the listener, so a config error fails the build | Fix the deserializers (not by skipping tests) |
| Starts but never receives events, `Connection to node -1 (localhost/127.0.0.1:9092)` | No bootstrap server set in the container | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, network `internal` |
| `InvalidProtocolBufferException` | Producer and consumer `.proto` differ, or a non-protobuf message on the topic | Keep both `.proto` files identical |
| Old events not received after changing `groupId` | A new group starts at the **latest** offset by default | Set `spring.kafka.consumer.auto-offset-reset=earliest` to read from the start |

---

## Improvements before production

1. **Dead-letter topic** - today a bad message is retried, logged and dropped. Configure a
   `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` so failed events go to
   `patient.DLT` for inspection and replay.
2. **Idempotent processing** - Kafka delivers *at least once*: after a crash or rebalance
   the same event can arrive twice. Use `patientId` (plus event type) to detect duplicates
   before counting/storing.
3. **Don't log personal data** - the listener logs name and email on every event; log the
   `patientId` only. The error log also prints the raw `byte[]` (as an array reference) -
   log `e` (with stack trace) and the topic/partition/offset instead.
4. **Handle `event_type`** - the topic will carry more than `PATIENT_CREATED`; switch on
   `patientEvent.getEventType()` and ignore unknown types instead of treating every event
   the same.
5. **Topic and group in config** - `@KafkaListener(topics = "${kafka.topic.patient}",
   groupId = "${spring.application.name}")` instead of string literals.
6. **Scale with partitions** - one partition = one active consumer per group. Create the
   topic with more partitions to run several analytics-service instances in parallel.
7. **Share the `.proto`** - copying it between services will drift. Move it to a shared
   module (or a schema registry) so producer and consumers always use the same contract.
8. **Set `server.port=4002`** to match the Dockerfile and port binding.
