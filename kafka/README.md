# Kafka Setup

How Kafka is run for this project: **locally** (a single Docker container created with
IntelliJ's Docker run configuration) and how that differs from **production**.

> How patient-service **publishes events** to Kafka: see [PRODUCER.md](PRODUCER.md).

- [Why Kafka here](#why-kafka-here)
- [Local setup (IntelliJ)](#local-setup-intellij)
- [Same setup with the Docker CLI](#same-setup-with-the-docker-cli)
- [Verify it works](#verify-it-works)
- [Connecting the services](#connecting-the-services)
- [Troubleshooting](#troubleshooting)
- [Local vs production](#local-vs-production)
- [Production setup](#production-setup)

---

## Why Kafka here

`patient-service` already calls `billing-service` over **gRPC** when it needs an answer
right away (create a billing account, get its ID back).

Kafka is for **events other services react to later**. `patient-service` publishes a
`PatientCreated` event to the `patient` topic and returns immediately; any number of
consumers (analytics, notifications, audit, ...) read it in their own time.

```
Client ─REST─► patient-service ─gRPC─► billing-service      (needs the result now)
                     │
                     └─► Kafka topic "patient" ─► analytics-service   (reacts later)
```

---

## Local setup (IntelliJ)

One container that is **both broker and controller** (KRaft mode - Kafka 4.x has no
ZooKeeper).

**Run > Edit Configurations > + > Docker > Docker Image**

| Field | Value |
|---|---|
| Name | `kafka` |
| Image ID or name | `apache/kafka:4.3.1` (pin a version, don't use `latest`) |
| Container name | `kafka` |
| Bind ports | `9092:9092`, `9094:9094` |
| Bind mounts (optional, keeps data) | named volume `kafka-data` → `/var/lib/kafka/data` |
| Run options | `--network internal` (same network as the other containers) |

**Environment variables** - paste into the field exactly as below
(`;` with **no space** after it, see [Troubleshooting](#troubleshooting)):

```
KAFKA_NODE_ID=0;KAFKA_PROCESS_ROLES=broker,controller;KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094;KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092,EXTERNAL://localhost:9094;KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER;KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT,PLAINTEXT:PLAINTEXT;KAFKA_CONTROLLER_QUORUM_VOTERS=0@kafka:9093;KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT;KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1;KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1;KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1;KAFKA_LOG_DIRS=/var/lib/kafka/data
```

### What each variable means

The `apache/kafka` image turns `KAFKA_SOME_NAME` into the Kafka setting `some.name`.

| Variable | Value | Why |
|---|---|---|
| `KAFKA_NODE_ID` | `0` | Unique ID of this node in the cluster |
| `KAFKA_PROCESS_ROLES` | `broker,controller` | One node does both jobs (fine locally, **not** in production) |
| `KAFKA_LISTENERS` | `PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094` | Ports Kafka listens on inside the container |
| `KAFKA_ADVERTISED_LISTENERS` | `PLAINTEXT://kafka:9092,EXTERNAL://localhost:9094` | Addresses Kafka tells clients to use (see below) |
| `KAFKA_CONTROLLER_LISTENER_NAMES` | `CONTROLLER` | Which listener the controller (cluster brain) uses |
| `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP` | `...:PLAINTEXT` | No encryption / auth - **local only** |
| `KAFKA_CONTROLLER_QUORUM_VOTERS` | `0@kafka:9093` | The controllers that vote on cluster state (just this node) |
| `KAFKA_INTER_BROKER_LISTENER_NAME` | `PLAINTEXT` | Listener brokers use to talk to each other |
| `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR` | `1` | Default is 3; with one broker consumer groups **never receive messages** without this |
| `KAFKA_TRANSACTION_STATE_LOG_*` | `1` | Same reason, for transactions |
| `KAFKA_LOG_DIRS` | `/var/lib/kafka/data` | Where messages are stored - mount a volume here to keep them |

### Why two listeners (9092 and 9094)?

A client first connects to any address, then Kafka replies *"from now on talk to me at
`<advertised listener>`"*. That address must be reachable **from where the client runs**:

| Client runs... | Bootstrap server | Advertised back |
|---|---|---|
| In Docker on the `internal` network (patient-service container) | `kafka:9092` | `kafka:9092` |
| On your Mac (IntelliJ run, Kafka UI, CLI) | `localhost:9094` | `localhost:9094` |

If you only had `kafka:9092`, a client on your Mac would connect, be told "use
`kafka:9092`", and fail - your Mac cannot resolve the name `kafka`.

---

## Same setup with the Docker CLI

Equivalent of the IntelliJ run configuration, useful for scripts or another machine:

```bash
docker network create internal          # once, if it doesn't exist
docker volume create kafka-data         # once, keeps topics/messages across restarts

docker run -d --name kafka --network internal \
  -p 9092:9092 -p 9094:9094 \
  -v kafka-data:/var/lib/kafka/data \
  -e KAFKA_NODE_ID=0 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092,EXTERNAL://localhost:9094 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=0@kafka:9093 \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_LOG_DIRS=/var/lib/kafka/data \
  apache/kafka:4.3.1
```

Without the volume, all topics and messages are lost when the container is removed.

---

## Verify it works

The container log should end with:

```
[KafkaRaftServer nodeId=0] Kafka Server started
```

Create a topic, send a message, read it back (all inside the container):

```bash
# create the topic
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --create --topic patient --partitions 3

# list topics
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list

# produce one message
echo "hello-patient" | docker exec -i kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 --topic patient

# consume it (as a consumer group, like a real service)
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic patient \
  --from-beginning --group test --max-messages 1
```

---

## Connecting the services

Spring Boot reads the address from `spring.kafka.bootstrap-servers`
(env var `SPRING_KAFKA_BOOTSTRAP_SERVERS`):

| Service runs | Value |
|---|---|
| As a container on `internal` | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |
| From IntelliJ on your Mac | `spring.kafka.bootstrap-servers=localhost:9094` |

Start order: `patient-service-db` and `kafka` first, then the services.

---

## Troubleshooting

| Error / symptom | Cause | Fix |
|---|---|---|
| `Missing required configuration "process.roles"` | Variables named `KAFKA_CFG_*` | That is the **Bitnami** image format. `apache/kafka` needs `KAFKA_*` (no `CFG_`) |
| Same error, names look right | A space before the name: `" KAFKA_PROCESS_ROLES"` | IntelliJ splits on `;` only - use `;` **without** a space, or the table editor (folder icon) |
| Consumer connects but never receives messages | Offsets topic replication factor is 3 on a 1-broker cluster | Set `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` |
| Client on your Mac hangs / `kafka: nodename nor servname` | It used `kafka:9092` | From the Mac use `localhost:9094` |
| Topics gone after recreating the container | No volume | Mount a volume at `KAFKA_LOG_DIRS` |
| `address already in use` | Something else on 9092 / 9094 | Change the host side of the port binding (e.g. `9095:9094`) and the advertised `EXTERNAL://localhost:9095` |

> Note: once you set **any** `KAFKA_*` variable, the image stops applying its built-in
> defaults, so every required setting above must be present.

---

## Local vs production

| Topic | Local (this project) | Production |
|---|---|---|
| Nodes | 1 container, broker + controller combined | **3+ brokers** and **3 dedicated controllers** (or a managed service) |
| Failure tolerance | Container stops = Kafka is down | Survives losing a broker / a controller |
| Replication factor | `1` (one copy of every message) | `3` copies, `min.insync.replicas=2` |
| Security | `PLAINTEXT` - no encryption, no login | **TLS** encryption + **SASL** authentication + **ACLs** per topic |
| Exposure | Ports published on your Mac | Private network only, never on the public internet |
| Storage | Optional Docker volume | Dedicated persistent disks per broker, sized for retention |
| Topics | Created by hand / auto-created | Created **explicitly** (CLI, Terraform, operator); `auto.create.topics.enable=false` |
| Config | Env vars in an IntelliJ run configuration | Version-controlled (Helm values, Terraform, Kubernetes manifests) |
| Secrets | None | Stored in a secret manager, never in code or images |
| Monitoring | Container logs | Metrics (consumer lag, under-replicated partitions, disk), alerts |
| Who runs it | You | A managed service or a platform team |

---

## Production setup

### Option 1 - Managed Kafka (recommended)

Running Kafka yourself is real operational work (upgrades, disks, rebalancing, security).
Most teams use a managed service and only configure topics and clients:

- **Amazon MSK** (AWS) - fits if you deploy on AWS
- **Confluent Cloud** - any cloud, from the company behind Kafka
- **Aiven**, **Azure Event Hubs (Kafka API)**, **Google Managed Service for Apache Kafka**

The services then only change their configuration - the code stays the same:

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}   # e.g. b-1.xxx.kafka.amazonaws.com:9096
spring.kafka.security.protocol=SASL_SSL
spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512
spring.kafka.properties.sasl.jaas.config=${KAFKA_JAAS_CONFIG}   # from the secret manager
```

### Option 2 - Self-managed on Kubernetes

Use an operator rather than plain containers - it handles rolling upgrades, certificates,
users and topics:

- **Strimzi** (open source, CNCF) - Kafka, topics (`KafkaTopic`) and users (`KafkaUser`)
  are Kubernetes resources kept in git.

### Settings that must change from the local setup

**Cluster (broker side)**

| Setting | Local | Production |
|---|---|---|
| `process.roles` | `broker,controller` | `broker` on brokers, `controller` on controllers |
| `controller.quorum.voters` / bootstrap | 1 voter | 3 controllers (tolerates 1 failure) |
| `default.replication.factor` | 1 | `3` |
| `min.insync.replicas` | 1 | `2` |
| `offsets.topic.replication.factor` | 1 | `3` |
| `transaction.state.log.replication.factor` | 1 | `3` |
| `transaction.state.log.min.isr` | 1 | `2` |
| `auto.create.topics.enable` | true (default) | `false` |
| `listener.security.protocol.map` | `PLAINTEXT` | `SASL_SSL` (clients), `SSL` (inter-broker / controller) |
| `log.retention.hours` | default (168 = 7 days) | Chosen per topic based on needs and disk |

**Producer (patient-service)** - don't lose events:

```properties
spring.kafka.producer.acks=all                  # wait until all in-sync replicas have it
spring.kafka.producer.properties.enable.idempotence=true   # no duplicates on retry
spring.kafka.producer.retries=2147483647
```

**Consumers (analytics-service, ...)** - process every event safely:

- Use a fixed `spring.kafka.consumer.group-id` per service.
- Make processing **idempotent** - Kafka delivers *at least once*, so the same event can
  arrive twice.
- Configure a **dead-letter topic** for events that keep failing, so one bad message
  doesn't block the partition.
- Watch **consumer lag** - growing lag means consumers can't keep up.

**Topics** - create them explicitly, e.g.:

```bash
kafka-topics.sh --bootstrap-server <broker>:9092 --command-config client.properties \
  --create --topic patient --partitions 6 --replication-factor 3 \
  --config min.insync.replicas=2
```

- Partitions = maximum parallel consumers in one group. You can add partitions later but
  not remove them, and adding them changes which partition a key goes to.
- Use the **patient ID as the message key**, so all events for one patient stay in order
  on the same partition.

### Production checklist

- [ ] Managed service or operator chosen; no single-node Kafka
- [ ] 3+ brokers across availability zones, 3 controllers
- [ ] Replication factor 3, `min.insync.replicas=2`
- [ ] TLS + SASL enabled, ACLs per service (patient-service may only *write* `patient`)
- [ ] Credentials in a secret manager, injected as env vars at runtime
- [ ] `auto.create.topics.enable=false`, topics defined in code/infra (git)
- [ ] Producer `acks=all` + idempotence; consumers idempotent with a dead-letter topic
- [ ] Event format agreed and versioned (Protobuf/Avro + Schema Registry, or documented JSON)
- [ ] Monitoring and alerts: consumer lag, under-replicated partitions, disk usage
- [ ] Kafka version pinned and upgraded deliberately
