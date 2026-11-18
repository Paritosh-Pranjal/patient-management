# api-gateway

Single entry point (`localhost:4004`) for the patient-management system.
Routes requests to the services and checks **JWT tokens** (via auth-service) before
protected routes are forwarded.

- [Request flow](#request-flow)
- [Files](#files)
- [Dependencies](#dependencies)
- [Routes](#routes)
- [JWT validation filter](#jwt-validation-filter)
- [Which routes are protected](#which-routes-are-protected)
- [Run in Docker (IntelliJ)](#run-in-docker-intellij)
- [Test it](#test-it)
- [Troubleshooting](#troubleshooting)
- [Improvements before production](#improvements-before-production)

---

## Request flow

```
Client
  │  GET /api/patients   Authorization: Bearer eyJ...
  ▼
api-gateway :4004
  ├─ Predicate  Path=/api/patients/**   → patient-service-route
  ├─ Filter 1   StripPrefix=1           /api/patients → /patients
  ├─ Filter 2   JwtValidation
  │     ├─ no "Bearer " header ─────────────► 401 (stop)
  │     └─ GET auth-service:4005/validate
  │            ├─ 401 ──────────────────────► 401 (stop)
  │            └─ 200 ──► continue
  ▼
patient-service :4000   GET /patients
```

- **Predicates** decide *whether* a route applies.
- **Filters** run *in the order listed* before the request is forwarded.

---

## Files

| File | Purpose |
|---|---|
| `src/main/resources/application.yml` | Port, `auth.service.url`, routes |
| `filter/JwtValidationGatewayFilterFactory.java` | Custom filter `JwtValidation` - calls auth-service `/validate` |
| `exception/JwtValidationException.java` | Turns auth-service's 401 into a 401 for the client |
| `Dockerfile` | Multi-stage build, exposes `4004` |

---

## Dependencies

| Dependency | Why |
|---|---|
| `spring-cloud-starter-gateway-server-webflux` | The gateway (reactive, Netty) |
| `spring-boot-starter-webclient` | Creates the `WebClient.Builder` bean used by the filter |
| `spring-cloud-dependencies` `2025.1.x` (BOM) | Spring Cloud versions for Boot 4.1 |

> **Spring Boot 4:** the `WebClient` *class* comes with WebFlux, but the
> `WebClient.Builder` *bean* is auto-configured only by `spring-boot-starter-webclient`.
> Without it: *"Could not autowire. No beans of 'Builder' type found"*.

---

## Routes

`application.yml` (Spring Cloud Gateway **5.x** - routes live under
`spring.cloud.gateway.server.webflux.routes`; the old `spring.cloud.gateway.routes` was removed):

```yaml
auth:
  service:
    url: http://auth-service:4005

spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: auth-service-route
              uri: http://auth-service:4005
              predicates:
                - Path=/auth/**
              filters:
                - StripPrefix=1

            - id: patient-service-route
              uri: http://patient-service:4000
              predicates:
                - Path=/api/patients/**
              filters:
                - StripPrefix=1
                - JwtValidation

            - id: api-docs-patient-route
              uri: http://patient-service:4000
              predicates:
                - Path=/api-docs/patients
              filters:
                - RewritePath=/api-docs/patients,/v3/api-docs

            - id: api-docs-auth-route
              uri: http://auth-service:4005
              predicates:
                - Path=/api-docs/auth
              filters:
                - RewritePath=/api-docs/auth,/v3/api-docs
```

| Client calls (`localhost:4004`) | Forwarded to | Token needed |
|---|---|---|
| `POST /auth/login` | auth-service `/login` | No |
| `GET /auth/validate` | auth-service `/validate` | (it's the check itself) |
| `/api/patients/**` | patient-service `/patients/**` | **Yes** |
| `GET /api-docs/patients` | patient-service `/v3/api-docs` | No |
| `GET /api-docs/auth` | auth-service `/v3/api-docs` | No |

Built-in filters used:

| Filter | Does |
|---|---|
| `StripPrefix=N` | Removes the first N path segments (`/api/patients/1` → `/patients/1`). Without it the service gets `/api/patients` → **404**. |
| `RewritePath=regex,replacement` | Replaces the path completely (`/api-docs/patients` → `/v3/api-docs`). |

Predicate and filter names are **case-sensitive** (`Path`, not `path`).

---

## JWT validation filter

```java
@Component
public class JwtValidationGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    private final WebClient webClient;

    public JwtValidationGatewayFilterFactory(WebClient.Builder webClientBuilder,
                                             @Value("${auth.service.url}") String authServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (token == null || !token.startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            return webClient.get()
                    .uri("/validate")
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()
                    .toBodilessEntity()
                    .then(chain.filter(exchange));
        };
    }
}
```

### How `JwtValidation` in the yml finds this class

The gateway registers every `GatewayFilterFactory` bean under its class name **minus the
`GatewayFilterFactory` suffix**:

| Class | Name in yml |
|---|---|
| `StripPrefixGatewayFilterFactory` (built-in) | `StripPrefix` |
| `JwtValidationGatewayFilterFactory` (ours) | `JwtValidation` |
| `CheckApiValidation` (no suffix) | `CheckApiValidation` |

Keep the suffix (the Spring Cloud Gateway docs recommend it), or override `name()` to
choose the yml name explicitly. A mismatch fails at startup with
*"Unable to find GatewayFilterFactory with name ..."*.

### What runs when

| When | What |
|---|---|
| Startup | One factory bean is created; the constructor builds the `WebClient`. |
| Startup | The gateway reads the yml and calls `apply(config)` **once per route** that lists `JwtValidation`. |
| Every request | Only the returned lambda `(exchange, chain) -> {...}` runs. |

- `exchange` = current request + response; `chain.filter(exchange)` = continue to the service.
- `setComplete()` = end the request here with the status set.
- Reactive (WebFlux): everything returns a `Mono`, nothing blocks.
- `Object config` - options from the yml (like the `1` in `StripPrefix=1`); unused here.

### Turning auth-service's 401 into a 401

If `/validate` returns 401, `retrieve()` raises `WebClientResponseException.Unauthorized`.
Without a handler the client would get **500**. `JwtValidationException` handles it:

```java
@RestControllerAdvice
public class JwtValidationException {
    @ExceptionHandler(WebClientResponseException.Unauthorized.class)
    public Mono<Void> handleUnauthorizedException(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
```

> Check this with the "modified token" test below: if the gateway still answers `500`,
> handle the error inside the filter instead - e.g. `.onErrorResume(WebClientResponseException.Unauthorized.class, e -> { set 401; return setComplete(); })`.

---

## Which routes are protected

A filter only runs on routes that **list it**. A route without `- JwtValidation` is open to
anyone who can reach the gateway - on a public server, that means the internet.

| Situation | Validated? |
|---|---|
| Route lists `- JwtValidation` | Yes |
| Route doesn't list it | **No - open** |
| Calling a service's own port directly (e.g. `:4000`), skipping the gateway | **No** - unless the port isn't published or the service validates too |

Public on purpose: `/auth/**` (users get their token there) and the API docs.

**Secure by default** alternative - apply to every route and open public paths explicitly:

```yaml
spring.cloud.gateway.server.webflux.default-filters:
  - JwtValidation
```

(The filter would then need to skip `/auth/**`, otherwise nobody can log in.)

---

## Run in Docker (IntelliJ)

| Container | Port binding | Network |
|---|---|---|
| `api-gateway` | `4004:4004` | `internal` |
| `auth-service` | none needed | `internal` |
| `patient-service` | none needed | `internal` |

Only the gateway needs a published port. Service names in `uri:` and `auth.service.url`
(`auth-service`, `patient-service`) resolve on the `internal` Docker network.

`auth.service.url` is in `application.yml`; override at runtime with the env var
`AUTH_SERVICE_URL=...` if needed.

> **Build-time vs run-time:** the Dockerfile runs `mvn clean package` with tests. The
> `contextLoads` test starts the app, so every `${...}` placeholder must be resolvable from
> `application.yml` - run-configuration env vars don't exist during `docker build`.

Start order: databases, Kafka → `billing-service` → `auth-service`, `patient-service` → `api-gateway`.

After changing code or `application.yml`, **rebuild** the image (run the Dockerfile run configuration).

---

## Test it

Files in `api-requests/`:

1. `auth-service/login.http` - `POST localhost:4004/auth/login`, saves the token to `{{token}}`.
2. `patient-service/get-patients.http` - sends `Authorization: Bearer {{token}}`.

| Request | Expected |
|---|---|
| `GET /api/patients/...` without a token | `401` |
| `POST /auth/login` (`testuser@test.com` / `password123`) | `200` + token |
| `GET /api/patients/...` with `Bearer <token>` | `200` |
| Same with a modified token | `401` |
| `GET /auth/validate` with the token | `200` |

Keep exactly **one** space after `Bearer` - auth-service reads the token with `substring(7)`.

---

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Cannot resolve configuration property 'spring.cloud.gateway.routes'` | Gateway 5.x renamed it | `spring.cloud.gateway.server.webflux.routes` |
| `Unable to find RoutePredicateFactory with name path` | Lowercase name | `Path=` (names are case-sensitive) |
| `Could not autowire. No beans of 'Builder' type found` | No WebClient auto-config (Boot 4) | `spring-boot-starter-webclient` |
| `package WebClient does not exist` / `cannot find symbol WebClieny` | Missing import / typo | `import org.springframework.web.reactive.function.client.WebClient;` |
| `must either be declared abstract or implement abstract method 'apply(C)'` | Method misspelled (`appply`) | `apply` (IntelliJ: ⌃O → Override Methods) |
| `Could not resolve placeholder 'auth.service.url'` during Docker build | Not in `application.yml` | Add `auth.service.url` to the yml |
| `404` for `/login` via gateway | Route is `/auth/**` | Call `/auth/login` |
| `500` instead of `401` for a bad token | `WebClientResponseException` not handled | `JwtValidationException` handler |
| `Unable to load io.netty.resolver.dns.macos...` when building on a Mac | Native DNS lib missing on macOS | Harmless warning; not present in the Linux container |

---

## Improvements before production

1. **Validate in the gateway itself** - verify the JWT signature locally (shared key or
   public key / JWKS) instead of an HTTP call to auth-service on every request.
2. **Secure by default** - `default-filters: JwtValidation` with an allow-list of public paths.
3. **Forward user info** - add headers like `X-User-Email` / `X-User-Role` from the token so
   services don't need to parse it.
4. **Handle auth-service being down** - timeouts and a clear `503` instead of a generic error.
5. **Defense in depth** - services also validate the token (Spring Security resource server),
   and only the gateway is exposed publicly.
6. **Rate limiting** (`RequestRateLimiter`) and **CORS** configuration at the gateway.
7. **Protect API docs** in production, or disable them.
