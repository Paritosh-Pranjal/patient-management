# Kafka Producer - patient-service

How `patient-service` publishes a **`PatientEvent`** to the Kafka topic **`patient`**
every time a patient is created.

Kafka itself (the broker container) is covered in [README.md](README.md).

- [What happens](#what-happens)
- [Files involved](#files-involved)
- [1. Dependency](#1-dependency)
- [2. The event contract (`.proto`)](#2-the-event-contract-proto)
- [3. Configuration](#3-configuration)
- [4. The producer class](#4-the-producer-class)
- [5. Calling it from `createPatient`](#5-calling-it-from-createpatient)
- [Run and verify](#run-and-verify)
- [Troubleshooting](#troubleshooting)
- [Improvements before production](#improvements-before-production)

---

## What happens

```
POST /patients
   │
   ▼
PatientService.createPatient()
   ├─ 1. save patient in Postgres
   ├─ 2. gRPC  → billing-service   (waits for accountId - needs the answer)
   └─ 3. Kafka → topic "patient"   (fire-and-forget event - nobody answers)
                     │
                     ▼
            future consumers (analytics, notifications, ...)
```

- **gRPC** = a *command* to one known service, caller waits for the result.
- **Kafka** = a *fact* ("patient created") that any number of services can react to
  later. patient-service doesn't know or care who listens.

The event is sent as **protobuf bytes** (`byte[]`), defined by `patient-event.proto`.

---

## Files involved

| File | Purpose |
|---|---|
| `patient-service/pom.xml` | `spring-boot-starter-kafka` dependency + protobuf plugin |
| `patient-service/src/main/proto/patient-event.proto` | Shape of the event (shared contract) |
| `patient-service/src/main/java/.../kafka/KafkaProducer.java` | Builds and sends the event |
| `patient-service/src/main/java/.../service/PatientService.java` | Calls `kafkaProducer.sendEvent(...)` |
| `patient-service/src/main/resources/application.properties` | Serializer settings |
| IntelliJ run config `patient-service` | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |

---

## 1. Dependency

```xml
<!-- Kafka: the starter brings spring-kafka + Spring Boot's auto-configuration (creates KafkaTemplate) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-kafka</artifactId>
</dependency>
```

> **Spring Boot 4 change:** `spring-kafka` alone is **not enough** any more. The
> auto-configuration that creates the `KafkaTemplate` bean moved into
> `spring-boot-kafka`, which comes with `spring-boot-starter-kafka`. Without the starter
> you get *"Could not autowire. No beans of 'KafkaTemplate' type found"*.

No version needed - Spring Boot 4.1.2 manages it (spring-kafka 4.1.2, kafka-clients 4.2.2).

The `protobuf-maven-plugin` + `os-maven-plugin` already added for gRPC also generate the
Java class for the event - nothing extra needed.

---

## 2. The event contract (`.proto`)

`patient-service/src/main/proto/patient-event.proto`

```protobuf
syntax = "proto3";

package patient.events;
option java_multiple_files = true;

message PatientEvent {
  string patientId = 1;
  string name = 2;
  string email = 3;
  string event_type = 4;
}
```

Kafka only stores bytes. This file is the **agreement between producer and consumers**
about what those bytes mean. Every consumer gets a copy of the same file and reads the
bytes back with `PatientEvent.parseFrom(bytes)`.

On `mvn compile` it generates (in `target/generated-sources/protobuf/java/patient/events/`):

| Class | What it is |
|---|---|
| `PatientEvent` | The message - has `newBuilder()`, `toByteArray()`, `parseFrom()` |
| `PatientEventOuterClass` | File metadata only (protoc renames it because the file name `patient-event` would clash with the message name) |

Rules:
- **Never change or reuse a field number** (`= 1`, `= 2` ...) - old events in Kafka still use them. Add new fields with new numbers.
- Keep the file **identical** in the producer and every consumer.
- `event_type` lets one topic carry several kinds of events (`PATIENT_CREATED`, `PATIENT_UPDATED`, ...).

> A `.proto` is **not required** by Kafka (JSON or plain strings also work). It's used here
> for a strict, compact, versionable contract - and because the project already uses
> protobuf for gRPC.

---

## 3. Configuration

`patient-service/src/main/resources/application.properties`

```properties
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer
```

The producer's `KafkaTemplate<String, byte[]>` sends a `String` key and a `byte[]` value,
so the serializers must match. Spring's default is `StringSerializer` for **both**, which
fails on `byte[]`.

**Serializer vs deserializer** - match the property and the class name:

| Role | Property | Class |
|---|---|---|
| **Producer** (sends) - patient-service | `spring.kafka.producer.*-serializer` | `...Serializer` |
| **Consumer** (reads) - e.g. analytics-service | `spring.kafka.consumer.*-deserializer` | `...Deserializer` |

**Kafka address** (`spring.kafka.bootstrap-servers`):

| patient-service runs | Set |
|---|---|
| As a container on the `internal` network | env var `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` (IntelliJ run config) |
| From IntelliJ on your Mac | `spring.kafka.bootstrap-servers=localhost:9094` |

> `application.properties` is packed into the jar in the Docker image. After changing it,
> **rebuild** the image (run the Dockerfile run configuration) - restarting the container
> keeps the old values. Env vars override the file without a rebuild, e.g.
> `SPRING_KAFKA_PRODUCER_VALUE_SERIALIZER=org.apache.kafka.common.serialization.ByteArraySerializer`.

---

## 4. The producer class

`patient-service/src/main/java/com/pm/patientservice/kafka/KafkaProducer.java`

```java
@Service
public class KafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducer.class);
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public KafkaProducer(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;   // created by Spring Boot's auto-configuration
    }

    public void sendEvent(Patient patient){
        log.info("Sending Patient to Kafka");
        PatientEvent event = PatientEvent.newBuilder()      // generated from the .proto
                .setPatientId(patient.getId().toString())
                .setName(patient.getName())
                .setEmail(patient.getEmail())
                .setEventType("PATIENT_CREATED")
                .build();

        try{
            kafkaTemplate.send("patient", event.toByteArray());   // topic, value (bytes)
        }
        catch(Exception e){
            log.error("Error sending PatientCreated event: {} -> {}", e, event);
        }
    }
}
```

- `event.toByteArray()` - turns the protobuf message into `byte[]` for the `ByteArraySerializer`.
- `send(...)` is **asynchronous**: it hands the message to a background sender and returns.
  The `catch` only sees errors that happen immediately (e.g. wrong serializer); broker
  errors later are not caught here - see [improvements](#improvements-before-production).
- IntelliJ may underline the `KafkaTemplate<String, byte[]>` constructor parameter even
  when it works - Spring Boot registers the bean as `KafkaTemplate<?, ?>`. If the app
  starts, it's only an editor warning.

---

## 5. Calling it from `createPatient`

```java
Patient newPatient = patientRepository.save(PatientMapper.toModel(patientRequestDto));

billingServiceGrpcClient.createBillingAccount(
        newPatient.getId().toString(), newPatient.getName(), newPatient.getEmail());

kafkaProducer.sendEvent(newPatient);
return PatientMapper.toDTO(newPatient);
```

The event is sent **after** the patient is saved, so it carries the generated `id`.

---

## Run and verify

Start order: `kafka` and `patient-service-db` → `billing-service` → `patient-service`.

1. Create a patient (`api-requests/patient-service/create-patient.http`) with an email
   that doesn't exist yet - a duplicate email throws before the event is sent.
2. patient-service logs should show the serializers and **no** `Error sending` line:
   ```
   key.serializer = class org.apache.kafka.common.serialization.StringSerializer
   value.serializer = class org.apache.kafka.common.serialization.ByteArraySerializer
   Sending Patient to Kafka
   ```
3. Read the topic:
   ```bash
   docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server kafka:9092 --topic patient --from-beginning
   ```
   The value is protobuf **binary**, so you see the text fields (id, name, email,
   `PATIENT_CREATED`) mixed with odd characters. That's expected - a real consumer decodes
   it with `PatientEvent.parseFrom(bytes)`.
4. Count messages per partition:
   ```bash
   docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic patient
   ```

---

## Troubleshooting

Errors hit while setting this up, in order (the last row is a common one not hit yet):

| Error | Cause | Fix |
|---|---|---|
| `Could not autowire. No beans of 'KafkaTemplate<String, byte[]>' type found` | Only `spring-kafka` in the pom (Spring Boot 4) | Use `spring-boot-starter-kafka` |
| `Cannot resolve method 'newBuilder' in 'PatientEvent'` | Message was named `PatientEvents`; `PatientEvent` was the file's outer class | Name the message `PatientEvent` (singular), recompile |
| `SerializationException: Can't convert value of class [B to class StringSerializer` | `[B` = `byte[]`; value serializer was the default `StringSerializer`. The properties set were **consumer** `...deserializer` settings | Set `spring.kafka.producer.value-serializer=...ByteArraySerializer` |
| `KafkaException: Failed to construct kafka producer` | Producer properties pointed to `StringDeserializer` / `ByteArrayDeserializer` classes | Use `StringSerializer` / `ByteArraySerializer` |
| Changes to `application.properties` have no effect | Container still runs the old image | Rebuild via the Dockerfile run configuration |
| Nothing in the topic, no error | Request failed earlier (e.g. *Email already exists*) | Use a new email; check the logs for the request |
| `Connection to node -1 (localhost/127.0.0.1:9092) could not be established` in Docker | Bootstrap server defaulted to `localhost` | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |

---

## Improvements before production

The current producer works for learning; these make it reliable:

1. **Send a key** - keeps all events for one patient in order on the same partition:
   ```java
   kafkaTemplate.send("patient", patient.getId().toString(), event.toByteArray());
   ```
2. **Log the real failure, including async ones** - pass the exception as the *last*
   argument, and handle the result of `send`:
   ```java
   kafkaTemplate.send("patient", key, event.toByteArray())
           .whenComplete((result, ex) -> {
               if (ex != null) {
                   log.error("Failed to send PatientCreated event {}", event, ex);
               } else {
                   log.info("Sent PatientCreated to {}-{}@{}",
                           result.getRecordMetadata().topic(),
                           result.getRecordMetadata().partition(),
                           result.getRecordMetadata().offset());
               }
           });
   ```
3. **Topic name in one place** - a constant or `@Value("${kafka.topic.patient:patient}")`
   instead of the string `"patient"` in code.
4. **Don't lose events** - producer settings for production:
   ```properties
   spring.kafka.producer.acks=all
   spring.kafka.producer.properties.enable.idempotence=true
   ```
5. **DB and Kafka can disagree** - if the app crashes after saving the patient but before
   the event is sent, the event is lost (and the save is not rolled back). The standard
   fix is the **transactional outbox pattern**: save the event in an `outbox` table in the
   same DB transaction, and publish it to Kafka from there.
6. **Create the topic explicitly** (partitions, replication factor 3) instead of relying
   on auto-creation - see the production section of [README.md](README.md).
7. **Share the `.proto`** with consumers through a common module instead of copying it.
