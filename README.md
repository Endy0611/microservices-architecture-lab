# microservice-lab

A minimal but *real*, runnable Spring Boot + Gradle project that exercises every
piece of a typical Keycloak-secured microservice stack:

- **Keycloak** — authentication server (realm, client, roles, users)
- **Spring Cloud Config Server** — externalized configuration
- **Eureka Discovery Server** — service registry
- **Spring Cloud Gateway** — single entry point, validates JWTs
- **Service A** — resource server, calls Service B via **OpenFeign**
- **Service B** — resource server, downstream service
- **Token relay** — the *real logged-in user's* identity is propagated from
  Gateway → Service A → Service B, not re-authenticated at each hop.

---

## 1. Project structure

```
microservice-lab/
├── keycloak/
│   ├── docker-compose.yml        # Keycloak (dev mode) + auto-imported realm
│   └── realm-export.json         # realm, client, roles, 2 test users
│
├── config-server/                # Spring Cloud Config Server (port 8888)
│   └── .../resources/config-repo/  # embedded "native" config repo:
│       ├── application.yml         #   shared by ALL services (Eureka URL, Keycloak issuer)
│       ├── api-gateway.yml
│       ├── service-a.yml
│       └── service-b.yml
│
├── discovery-server/             # Eureka (port 8761)
├── api-gateway/                  # Spring Cloud Gateway (port 9090)
├── service-a/                    # calls service-b via Feign (port 8081)
├── service-b/                    # downstream resource server (port 8082)
│
├── docker-compose.yml            # OPTIONAL: full stack in containers
└── settings.gradle               # multi-module Gradle build
```

Every service (except discovery-server itself) pulls its port, its Keycloak
issuer URI, and its Eureka registration URL from the **Config Server**
instead of hardcoding them — that's the "externalized configuration" piece.

---

## 2. Prerequisites

- **JDK 17+**
- **Gradle 8.5+** installed locally (`gradle -v`), OR generate the wrapper
  yourself once with `gradle wrapper` inside this folder (a wrapper jar isn't
  included here since it's a binary file — see note at the bottom).
- **Docker** (for Keycloak only — everything else runs as a plain JVM process
  for easy testing)

---

## 3. Run order

Each command is run from the project root, one per terminal tab.

### Step 1 — Start Keycloak (with realm auto-imported)

```bash
cd keycloak
docker compose up
```

This starts Keycloak on `http://localhost:8080` and auto-imports:

| Item | Value |
|---|---|
| Realm | `lab-realm` |
| Client | `gateway-client` (public, direct-access-grant enabled for easy curl testing) |
| User 1 | `john` / `john123` — role `USER` |
| User 2 | `admin-user` / `admin123` — roles `USER`, `ADMIN` |

Admin console: `http://localhost:8080` → login `admin` / `admin`.

### Step 2 — Config Server

```bash
./gradlew :config-server:bootRun
```
Verify: `curl http://localhost:8888/service-a/default` should return JSON config.

### Step 3 — Discovery Server (Eureka)

```bash
./gradlew :discovery-server:bootRun
```
Verify: open `http://localhost:8761` — registry should be empty for now.

### Step 4 — Service B

```bash
./gradlew :service-b:bootRun
```
Wait until it registers in Eureka (`SERVICE-B` shows up on the dashboard).

### Step 5 — Service A

```bash
./gradlew :service-a:bootRun
```
Confirm `SERVICE-A` also registers in Eureka.

### Step 6 — API Gateway

```bash
./gradlew :api-gateway:bootRun
```

---

## 4. Get a token and test

### Get an access token from Keycloak (password grant — fine for local testing)

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8080/realms/lab-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=gateway-client" \
  -d "username=john" \
  -d "password=john123" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo $TOKEN
```

### Call Service A through the Gateway

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/api/a/hello
```

Expected response — notice `currentUser` appears **twice**, once from Service
A reading the JWT directly, and once inside `serviceBResponse` from Service B
independently reading the *same* JWT it received via Feign:

```json
{
  "service": "service-a",
  "message": "Hello from Service A",
  "currentUser": { "subject": "...", "preferredUsername": "john", "realmRoles": ["USER"] },
  "serviceBResponse": {
    "service": "service-b",
    "message": "Hello from Service B",
    "currentUser": { "subject": "...", "preferredUsername": "john", "realmRoles": ["USER"] }
  }
}
```

### Call Service B directly (bypassing Service A, still through the Gateway)

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/api/b/whoami
```

### No token → 401

```bash
curl -i http://localhost:9090/api/a/hello   # -> 401 Unauthorized
```

---

## 5. How "get current user on another service" actually works here

This is the **token relay** pattern — the standard way to propagate identity
across microservices without a service having to re-authenticate on behalf
of the user:

1. The client authenticates against **Keycloak** and gets a JWT access token.
2. The client calls the **Gateway** with `Authorization: Bearer <token>`.
   The Gateway validates the token's signature/issuer/expiry
   (`GatewaySecurityConfig`) and — because Spring Cloud Gateway forwards
   headers by default — passes that *same* header downstream untouched.
3. **Service A** receives the request, also validates the JWT
   (`SecurityConfig`), and can read the caller's identity straight off it
   via `@AuthenticationPrincipal Jwt jwt` (`ServiceAController.whoami`).
4. When Service A calls **Service B** through the `ServiceBClient` Feign
   interface, a `RequestInterceptor`
   (`FeignClientConfig.bearerTokenRelayInterceptor`) copies the *incoming*
   `Authorization` header onto the *outgoing* Feign call.
5. **Service B** validates that same JWT independently and reads the same
   `preferred_username` / `sub` / `realm_access.roles` claims — so B never
   has to trust A's word for who the user is; it verifies it itself.

This means every hop in the chain is independently secured, and the
identity of the real end user survives the whole call chain — that's the
"connect service to service while knowing who the user is" requirement.

> For pure machine-to-machine calls (no human user in the loop), the
> alternative is a `client_credentials` grant per service instead of
> relaying a user token — worth knowing about but not what's wired up here.

---

## 6. Optional: full Docker Compose stack

Once you're happy with local testing, you can containerize everything:

```bash
./gradlew bootJar   # builds a jar for every module
docker compose up --build
```

⚠️ **Known caveat:** Keycloak stamps the JWT's `iss` (issuer) claim with
whatever hostname/port the *token request* came in on. If you get the token
via `localhost:8080` but the other services validate it as `keycloak:8080`
(the Docker network name), the issuer won't match and validation will fail.
For a fully containerized test, either get the token via
`http://keycloak:8080/...` from inside the Docker network too, or set
Keycloak's `KC_HOSTNAME` to a single consistent hostname used by everyone.
For everyday testing, running Keycloak in Docker and everything else as
local JVM processes (Section 3) avoids this entirely.

---

## 7. Notes on the Gradle wrapper

This project ships `settings.gradle` / `build.gradle` files but not a
`gradle-wrapper.jar` (it's a binary and shouldn't be hand-generated). To get
`./gradlew` working:

```bash
gradle wrapper --gradle-version 8.7
```

Run that once inside the project root with any local Gradle install, and
`./gradlew` will work for everyone else who clones the project afterward.
Alternatively, just use your own installed Gradle directly:
`gradle :config-server:bootRun`, etc.

---

## 8. What you should have installed before starting

- JDK 17+
- Gradle 8.5+ (or generate the wrapper as above)
- Docker + Docker Compose (for Keycloak)
- `curl` and (optionally) `python3` or `jq` for parsing tokens in test commands

## 9. What to do, in order

1. `docker compose up` in `keycloak/` (realm/client/users auto-created).
2. Start `config-server`, then `discovery-server`.
3. Start `service-b`, then `service-a` (order matters so Eureka has B
   registered before A's Feign calls need to resolve it — though Feign will
   retry briefly either way).
4. Start `api-gateway`.
5. Get a token from Keycloak, call `/api/a/hello` through the gateway, and
   confirm the same user identity shows up in both Service A's and Service
   B's parts of the response.
