# auth-service

Issues and validates **JWT tokens** for the patient-management system.
Clients log in through the **api-gateway** and send the token on later requests.

- [What it does](#what-it-does)
- [Files](#files)
- [Dependencies](#dependencies)
- [Database](#database)
- [JWT](#jwt)
- [Endpoints](#endpoints)
- [API Gateway route](#api-gateway-route)
- [Run in Docker (IntelliJ)](#run-in-docker-intellij)
- [Test it](#test-it)
- [Troubleshooting](#troubleshooting)
- [Improvements before production](#improvements-before-production)

---

## What it does

```
Client ──► api-gateway :4004 ──► auth-service :4005 ──► Postgres (users table)
           /auth/**  (StripPrefix=1)

POST /login     email + password ─► BCrypt check ─► signed JWT (10 h)
GET  /validate  Authorization: Bearer <token> ─► 200 OK / 401 Unauthorized
```

The token carries the user's **email** (`sub`) and **role** (`role` claim) and is signed
with HMAC-SHA (HS256) using a shared secret (`JWT_SECRET`).

---

## Files

| File | Purpose |
|---|---|
| `controller/AuthController.java` | `POST /login`, `GET /validate` |
| `service/AuthService.java` | Checks password, creates token; turns JWT errors into `true/false` |
| `util/JwtUtil.java` | Builds and verifies JWTs (JJWT 0.12) |
| `config/SecurityConfig.java` | Permits all requests (the service does its own checks), disables CSRF, `BCryptPasswordEncoder` bean |
| `modal/User.java` | JPA entity → table `users` |
| `repository/UserRepository.java` | `findByEmail` |
| `dto/LoginRequestDTO.java` / `LoginResponseDTO.java` | Request `{email, password}`, response `{token}` |
| `resources/data.sql` | Creates `users` and seeds one test user |
| `Dockerfile` | Multi-stage build, exposes `4005` |
| `../api-requests/auth-service/*.http` | IntelliJ HTTP requests for login / validate |

---

## Dependencies

| Dependency | Why |
|---|---|
| `spring-boot-starter-web` | REST controller |
| `spring-boot-starter-security` | `PasswordEncoder` (BCrypt), `SecurityFilterChain` |
| `spring-boot-starter-data-jpa` + `postgresql` | `users` table |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` `0.12.6` | Create and parse JWTs |
| `springdoc-openapi-starter-webmvc-ui` | `@Operation` docs |
| `lombok` | Getters/setters |

> `spring-boot-starter-test` must appear **only once** - a duplicate makes Maven refuse
> to read the POM (`dependencies.dependency... must be unique`).

---

## Database

`User` is mapped to the table **`users`**:

```java
@Entity
@Table(name = "users")
public class User { ... }
```

Hibernate does **not** pluralise names - without `@Table` the table would be `user`,
which is a **reserved word** in Postgres (`create table user` → `syntax error at or near "user"`).

`data.sql` creates the table if missing and inserts a test user:

| email | password | role |
|---|---|---|
| `testuser@test.com` | `password123` (stored as a BCrypt hash) | `ADMIN` |

Spring Boot only runs `data.sql` against a non-embedded database when
`SPRING_SQL_INIT_MODE=always` is set.

---

## JWT

`JwtUtil` reads `jwt.secret` (env var `JWT_SECRET`), **Base64-decodes** it and builds
an HMAC key. The secret must be valid Base64 and at least 32 bytes. Generate one with:

```bash
openssl rand -base64 32
```

Never commit the secret - set it only as an environment variable.

Token rules:

- **Expiry** - `now + 10 hours`, computed per token:
  `new Date(System.currentTimeMillis() + EXPIRATION_MS)`.
  (`new Date(EXPIRATION_MS)` alone would mean 1970 → every token already expired.)
- **Validation** - `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`
  checks signature and expiry. Bad signature → `io.jsonwebtoken.security.SignatureException`
  (not `java.security.SignatureException`), anything else → `JwtException`.

---

## Endpoints

### `POST /login`

```json
{ "email": "testuser@test.com", "password": "password123" }
```

| Result | Response |
|---|---|
| Email exists and password matches | `200` `{ "token": "<jwt>" }` |
| Otherwise | `401` |

### `GET /validate`

Header: `Authorization: Bearer <token>`

| Result | Response |
|---|---|
| Header missing / not `Bearer ` | `401` |
| Bad signature, expired, malformed | `401` |
| Valid | `200` |

---

## API Gateway route

`api-gateway/src/main/resources/application.yml`:

```yaml
- id: auth-service-route
  uri: http://auth-service:4005
  predicates:
    - Path=/auth/**
  filters:
    - StripPrefix=1
```

| Client calls (gateway) | Forwarded to auth-service |
|---|---|
| `POST http://localhost:4004/auth/login` | `POST /login` |
| `GET  http://localhost:4004/auth/validate` | `GET /validate` |

Gateway 5.x notes (Spring Cloud 2025.1):

- Routes live under `spring.cloud.gateway.server.webflux.routes`
  (the old `spring.cloud.gateway.routes` was removed).
- Predicate/filter names are **case-sensitive**: `Path`, `StripPrefix`, `RewritePath`.
  `path=` fails with `Unable to find RoutePredicateFactory with name path`.

---

## Run in Docker (IntelliJ)

Containers on the `internal` network:

| Container | Image / Dockerfile | Port |
|---|---|---|
| `auth-service-db` | `postgres` | `5432` internal |
| `auth-service` | `auth-service/Dockerfile` | `4005` |
| `api-gateway` | `api-gateway/Dockerfile` | `4004:4004` |

`auth-service` environment variables (separate with `;`, **no spaces**):

```
SPRING_DATASOURCE_URL=jdbc:postgresql://auth-service-db:5432/db
SPRING_DATASOURCE_USERNAME=admin_user
SPRING_DATASOURCE_PASSWORD=<db password>
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_SQL_INIT_MODE=always
JWT_SECRET=<output of openssl rand -base64 32>
```

Adjust the DB host, name and credentials to match your Postgres container.

Start order: `auth-service-db` → `auth-service` → `api-gateway`.

Changing env vars only needs a container restart; changing code or
`application.properties` needs an image rebuild.

The Dockerfile builds with `-DskipTests` - the `contextLoads` test needs a database,
which isn't available during `docker build`.

---

## Test it

`api-requests/auth-service/login.http` stores the token in a global variable,
`validate.http` sends it:

```http
POST http://localhost:4004/auth/login
Content-Type: application/json

{ "email": "testuser@test.com", "password": "password123" }

> {% client.global.set("token", response.body.token) %}
```

```http
GET http://localhost:4004/auth/validate
Authorization: Bearer {{token}}
```

> Keep the `/auth` prefix - the gateway only routes `/auth/**` to auth-service
> (`/login` alone returns 404). Keep a **single** space after `Bearer`, because the
> controller takes `substring(7)`.

Or directly against the service (skipping the gateway) - needs `-p 4005:4005` on the container:

```bash
curl -X POST localhost:4005/login -H 'Content-Type: application/json' \
  -d '{"email":"testuser@test.com","password":"password123"}'
```

---

## Troubleshooting

Errors hit while building this service:

| Error | Cause | Fix |
|---|---|---|
| `dependencies.dependency... must be unique: spring-boot-starter-test` | Dependency declared twice | Keep one |
| `Failed to determine a suitable driver class` during `mvn package` | `contextLoads` test starts JPA without a DB | `-DskipTests` in Dockerfile, or add H2 (`runtime`) for tests |
| `syntax error at or near "user"` | Table named after `User` → reserved word | `@Table(name = "users")` |
| `IllegalArgumentException: Last unit does not have enough valid bits` in `JwtUtil` | `JWT_SECRET` isn't valid Base64 | `openssl rand -base64 32` |
| `Exception 'java.security.SignatureException' is never thrown` | Wrong import | `io.jsonwebtoken.security.SignatureException` |
| Every token rejected | Expiry was `new Date(EXPIRATION_MS)` (1970) | `System.currentTimeMillis() + EXPIRATION_MS` |
| `return` inside `? :` doesn't compile | Ternary is an expression | `return cond ? a : b;` |
| Gateway `Unable to find RoutePredicateFactory with name path` | Lowercase name | `Path=` |

---

## Improvements before production

1. **Secret management** - `JWT_SECRET` from a secret store (Vault, AWS Secrets Manager, K8s Secret), and rotate it.
2. **Validate tokens at the gateway** - a gateway filter that calls `/validate` (or verifies the JWT itself) before routing to patient-service; today the patient routes are open.
3. **Login validation** - add `@Valid` handling and return `400` for bad input instead of relying on `@Validated` defaults.
4. **Don't seed an ADMIN user in production** - keep `data.sql` for local only (profile-specific).
5. **Expiry and refresh** - shorter access tokens (e.g. 15 min) plus refresh tokens.
6. **Expose roles** - add the `role` claim check (`ADMIN`, `USER`) where endpoints need it.
7. **Rate-limit `/login`** to slow down password guessing.
8. **springdoc version** - `2.6.0` targets Spring Boot 3; use the springdoc release built for Boot 4.
