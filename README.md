# Fitness Microservices

A cloud-native fitness tracking platform built with Spring Boot microservices and a React frontend. Users log workouts; the backend asynchronously generates AI-powered fitness recommendations via Google Gemini and streams them back to the UI.

---

## Architecture

```
                                Browser (React, Vite)
                                       :5173
                                          |
                                    [Keycloak Login]
                                          |   (PKCE, JWT)
                                          v
                                   API Gateway :8080
                                   |  - JWT validation (OAuth2 Resource Server)
                                   |  - KeycloakUserSyncFilter (auto-registers users)
                                   |  - Route via Eureka load balancer
            +----------------------+----------------------+
            |                      |                      |
            v                      v                      v
       User Service          Activity Service         AI Service
          :8081                  :8082                   :8083
            |                      |                      ^
            v                      v                      |
       PostgreSQL              MongoDB                 MongoDB
    (fitness-demo-user)   (aiactivityfitness)   (airecommendationfitness)
                                  |                      ^
                                  | publish              | consume
                                  +---------> Kafka -----+
                                          (topic: activity-events)
                                                  |
                                                  v
                                          Google Gemini API
                                          (prompt -> JSON analysis)


Supporting infrastructure:
  Config Server  :8888   (centralised application config)
  Eureka         :8761   (service registry / discovery)
  Keycloak       :8181   (OIDC provider, realm: fitness-app)
```

### Request flow

1. User authenticates against Keycloak (Authorization Code + PKCE flow). The frontend receives a JWT and stores it plus the `sub` claim (as `userId`) in localStorage.
2. Every Axios request adds `Authorization: Bearer <jwt>` and `X-User-Id: <sub>` headers.
3. The gateway validates the JWT against Keycloak's JWK set, runs `KeycloakUserSyncFilter` (registers first-time users in the user-service automatically), then routes via `lb://<service-name>` through Eureka.
4. `POST /api/activities` persists to MongoDB and publishes the activity onto Kafka topic `activity-events`.
5. The ai-service Kafka listener consumes the event, asks Google Gemini for an analysis, and stores the recommendation in its own MongoDB collection.
6. The frontend fetches the recommendation via `GET /api/recommendation/activity/{id}`.

---

## Tech Stack

### Backend

| Concern                   | Technology                                              |
|---------------------------|---------------------------------------------------------|
| Language / build          | Java 17, Maven                                          |
| Framework                 | Spring Boot 3.x                                         |
| Service discovery         | Netflix Eureka (Spring Cloud)                           |
| API gateway               | Spring Cloud Gateway (reactive, WebFlux)                |
| Centralised config        | Spring Cloud Config Server (native classpath profile)   |
| Auth                      | Keycloak (OIDC / OAuth2), Spring Security Resource Server |
| Inter-service HTTP        | Spring `WebClient` (reactive)                           |
| Messaging                 | Apache Kafka (`spring-kafka`)                           |
| Document store            | MongoDB (Spring Data MongoDB)                           |
| Relational store          | PostgreSQL (Spring Data JPA, Hibernate)                 |
| Validation                | `spring-boot-starter-validation` (Jakarta Validation)   |
| Boilerplate               | Lombok                                                  |
| AI integration            | Google Gemini REST API                                  |

### Frontend

| Concern                  | Technology                            |
|--------------------------|---------------------------------------|
| Library                  | React 19                              |
| Build tool / dev server  | Vite 8                                |
| Language                 | JavaScript (JSX, ES modules)          |
| UI components            | MUI (Material-UI) 9                   |
| Styling                  | Emotion (peer of MUI)                 |
| State management         | Redux Toolkit + react-redux           |
| Routing                  | react-router 7                        |
| HTTP client              | axios                                 |
| Auth (OIDC PKCE)         | react-oauth2-code-pkce                |
| Linting                  | ESLint                                |

### Infrastructure (external prerequisites)

- Keycloak 24+
- Kafka 3.x (with a Zookeeper or KRaft setup)
- MongoDB 6+
- PostgreSQL 15+

---

## Services

### Config Server — port `8888`

Serves YAML configuration from its classpath (`src/main/resources/config/`) to every other microservice on startup.

Files served:
- `gateway-service.yml`
- `activity-service.yml`
- `ai-service.yml`
- `user-service.yml`

### Eureka — port `8761`

Standalone Eureka registry. Dashboard at `http://localhost:8761`. All other services register as Eureka clients.

### API Gateway — port `8080`

Spring Cloud Gateway, reactive. Single public entry point for the frontend.

Responsibilities:
- OAuth2 JWT validation against `http://localhost:8181/realms/fitness-app/protocol/openid-connect/certs`.
- CORS for the Vite dev server at `http://localhost:5173`.
- `KeycloakUserSyncFilter` — parses the inbound JWT, calls user-service to check if the user exists, and registers them on first request (using `sub`, `email`, `given_name`, `family_name` claims). Forwards downstream with `X-User-ID` header.

Routes:

| Path                       | Downstream            |
|----------------------------|-----------------------|
| `/api/users/**`            | `lb://USER-SERVICE`   |
| `/api/activities/**`       | `lb://ACTIVITY-SERVICE` |
| `/api/recommendation/**`   | `lb://AI-SERVICE`     |

### User Service — port `8081`

PostgreSQL-backed account service. Stores the Keycloak `sub` (UUID) alongside profile fields.

Endpoints:

| Method | Path                              | Description                                  |
|--------|-----------------------------------|----------------------------------------------|
| POST   | `/api/users/register`             | Register a user from Keycloak claims         |
| GET    | `/api/users/{userId}`             | Return user profile                          |
| GET    | `/api/users/{userId}/validate`    | Boolean check used by other services         |

Database: PostgreSQL `fitness-demo-user`. Table `users` with `id` (UUID), `keycloakId`, `email` (unique), `firstname`, `lastname`, `role`, `createdAt`, `updatedAt`.

### Activity Service — port `8082`

Tracks workouts and emits activity events to Kafka for downstream AI processing.

Endpoints:

| Method | Path                  | Headers       | Description                                |
|--------|-----------------------|---------------|--------------------------------------------|
| POST   | `/api/activities`     | `X-User-ID`   | Save a new activity; publishes to Kafka    |
| GET    | `/api/activities`     | `X-User-ID`   | List the caller's activities               |

- Validates the user via `UserValidationService` (WebClient call to user-service) before persisting.
- Publishes to Kafka topic `activity-events` (key = `userId`, value = JSON-serialised `Activity`).
- MongoDB database `aiactivityfitness`. Document: `id`, `userId`, `type` (RUNNING / CYCLING / SWIMMING), `duration`, `caloriesBurned`, `startTime`, `additionalMetrics`, `createdAt`, `updatedAt`.

### AI Service — port `8083`

Kafka consumer + Gemini integration. Generates structured recommendations and serves them via HTTP.

Pipeline:
1. `ActivityMessageListener` (Kafka `@KafkaListener`) consumes `activity-events` (consumer group `activity-processor-group`).
2. `ActivityAIService` builds a fitness-specific JSON prompt.
3. `GeminiService` posts to the Gemini REST API.
4. The response is parsed into structured sections: `analysis`, `improvements[]`, `suggestions[]`, `safety[]`.
5. `RecommendationRepository.save(...)` persists the result.

Endpoints:

| Method | Path                                       | Description                              |
|--------|--------------------------------------------|------------------------------------------|
| GET    | `/api/recommendation/user/{userId}`        | All recommendations for a user           |
| GET    | `/api/recommendation/activity/{activityId}`| Single recommendation for an activity    |

MongoDB database `airecommendationfitness`. Document: `id`, `activityId`, `userId`, `type`, `recommendation`, `improvements`, `suggestions`, `safety`, `createdAt`.

Required environment variables:
- `GEMINI_URL` — Gemini API endpoint
- `GEMINI_KEY` — API key

---

## Frontend (`fitness-frontend/`)

Vite + React SPA. Dev server runs at `http://localhost:5173`.

### Auth

`react-oauth2-code-pkce` handles the OIDC Authorization Code + PKCE flow against Keycloak. The configuration lives in `src/store/authConfig.js`:

| Setting                  | Value                                                                              |
|--------------------------|------------------------------------------------------------------------------------|
| `clientId`               | `oauth2-pkce-client`                                                               |
| `authorizationEndpoint`  | `http://localhost:8181/realms/fitness-app/protocol/openid-connect/auth`            |
| `tokenEndpoint`          | `http://localhost:8181/realms/fitness-app/protocol/openid-connect/token`           |
| `logoutEndpoint`         | `http://localhost:8181/realms/fitness-app/protocol/openid-connect/logout`          |
| `redirectUri`            | `http://localhost:5173`                                                            |
| `scope`                  | `openid profile email offline_access`                                              |

Keycloak client requirements (in the `fitness-app` realm):
- Client authentication: **Off** (public PKCE client)
- Standard flow: **On**
- Valid redirect URIs: `http://localhost:5173/*`
- Valid post-logout redirect URIs: `http://localhost:5173/*`
- Web origins: `http://localhost:5173` (or `+`)

### State

Redux Toolkit store at `src/store/store.js` with one slice (`authSlice`) that mirrors `token` / `user` / `userId` into localStorage so the Axios interceptor can stamp them onto every request.

### Components

| File                                  | Purpose                                                                 |
|---------------------------------------|-------------------------------------------------------------------------|
| `src/main.jsx`                        | Mounts React, wraps app in `<AuthProvider>` and `<Provider>`            |
| `src/App.jsx`                         | Router, auth state, layout, route definitions                           |
| `src/components/ActivityForm.jsx`     | Form to log a new activity (POST `/api/activities`)                     |
| `src/components/ActivityList.jsx`     | Lists the user's activities (GET `/api/activities`)                     |
| `src/components/ActivityDetail.jsx`   | Activity + AI recommendation view (GET `/api/recommendation/activity/{id}`) |
| `src/services/api.js`                 | Axios instance, auth interceptor, endpoint functions                    |

---

## Prerequisites

| Tool             | Version (tested) |
|------------------|------------------|
| JDK              | 17               |
| Maven            | 3.9+             |
| Node.js          | 20+              |
| MongoDB          | 6+               |
| PostgreSQL       | 15+              |
| Apache Kafka     | 3.x              |
| Keycloak         | 24+              |

Keycloak setup:
- Create realm `fitness-app`.
- Create client `oauth2-pkce-client` (public, PKCE, standard flow).
- Configure redirect URIs and web origins as listed in the Frontend section.
- Create at least one user.

---

## Configuration & Secrets

This project intentionally externalises sensitive values. **None of the following should ever be committed to git.**

### What is sensitive

| Value                          | Where it's used         | How to provide it                                  |
|--------------------------------|-------------------------|----------------------------------------------------|
| `GEMINI_KEY`                   | ai-service              | Environment variable (already externalised in YAML) |
| `GEMINI_URL`                   | ai-service              | Environment variable                               |
| PostgreSQL password            | user-service            | Environment variable / external config (see below) |
| Keycloak admin credentials     | Keycloak instance       | Set when starting Keycloak; never in repo          |
| MongoDB credentials (if used)  | activity / ai services  | Environment variable                               |

### How to set them

**Option A — environment variables** (recommended for local dev). Each Spring Boot service reads `${VAR_NAME}` placeholders from its `application.yml` at startup. Set them in your shell or IDE run configuration:

```bash
# bash / git-bash on Windows
export GEMINI_URL="https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
export GEMINI_KEY="your-real-key"
export POSTGRES_PASSWORD="your-db-password"
```

```powershell
# PowerShell
$env:GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/..."
$env:GEMINI_KEY = "your-real-key"
$env:POSTGRES_PASSWORD = "your-db-password"
```

Then in `application.yml` reference them like `password: ${POSTGRES_PASSWORD}`.

**Option B — local profile file**. Create `application-local.yml` next to `application.yml` in each service (or in the config-server's `config/` folder), put real values there, and run with `--spring.profiles.active=local`. The `application-local.yml` file is in `.gitignore` and never reaches GitHub.

### .gitignore

A root-level `.gitignore` (alongside the frontend's own one inside `fitness-frontend/`) covers build outputs, IDE files, logs, and any local secrets. It contains at least:

```gitignore
# Build outputs
**/target/
**/build/
**/dist/

# IDE
.idea/
*.iml
.vscode/
*.suo
.DS_Store

# Logs
*.log
**/logs/

# Local environment / secrets
.env
.env.*
!.env.example
**/application-local.yml
**/application-local.properties
**/application-secret.yml
```

### If a secret has already been committed

Adding it to `.gitignore` after the fact does **not** remove it from git history. You must:

1. **Rotate the secret immediately** (change the DB password, regenerate the Gemini key). Treat it as leaked.
2. Optionally rewrite history with `git filter-repo` (or BFG Repo-Cleaner) to scrub the value from past commits — but this is destructive and only matters if the repo is private. Anything that's been on a public remote should be considered permanently exposed.

### Frontend env

The frontend has no secrets baked in — the OAuth client is public (PKCE flow), and the Gemini key never touches the browser. If you ever need a frontend-side variable, use Vite's `VITE_` prefix in a `.env.local` file (which is already covered by the standard Vite `.gitignore` rules in `fitness-frontend/.gitignore`).

---

## Running locally

Start infrastructure first (MongoDB, PostgreSQL, Kafka, Keycloak), then the services in this order so each finds its config and registers cleanly:

```bash
# 1. Config server
cd configserver/configserver
mvn spring-boot:run

# 2. Eureka
cd ../../eureka/eureka
mvn spring-boot:run

# 3. Gateway
cd ../../gateway/gateway
mvn spring-boot:run

# 4. User service
cd ../../usersevice\ \(1\)/userservice
mvn spring-boot:run

# 5. Activity service
cd ../../activityservice/activityservice
mvn spring-boot:run

# 6. AI service (export Gemini credentials first)
export GEMINI_URL=https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent
export GEMINI_KEY=your-api-key
cd ../../aiservice/aiservice
mvn spring-boot:run

# 7. Frontend
cd ../../fitness-frontend
npm install
npm run dev
```

Open `http://localhost:5173`, click **LOGIN**, authenticate against Keycloak, and you're in.

---

## API reference

All requests go through the gateway at `http://localhost:8080`. Required headers (auto-added by the frontend Axios interceptor):

```
Authorization: Bearer <jwt-from-keycloak>
X-User-Id: <keycloak-sub-uuid>
```

| Method | URL                                          | Service          |
|--------|----------------------------------------------|------------------|
| POST   | `/api/users/register`                        | user-service     |
| GET    | `/api/users/{userId}`                        | user-service     |
| GET    | `/api/users/{userId}/validate`               | user-service     |
| POST   | `/api/activities`                            | activity-service |
| GET    | `/api/activities`                            | activity-service |
| GET    | `/api/recommendation/user/{userId}`          | ai-service       |
| GET    | `/api/recommendation/activity/{activityId}`  | ai-service       |

---

## Port reference

| Service          | Port  |
|------------------|-------|
| Frontend (Vite)  | 5173  |
| API Gateway      | 8080  |
| User Service     | 8081  |
| Activity Service | 8082  |
| AI Service       | 8083  |
| Eureka           | 8761  |
| Config Server    | 8888  |
| Keycloak         | 8181  |
| Kafka            | 9092  |
| MongoDB          | 27017 |
| PostgreSQL       | 5432  |

---

## Repository layout

```
fitness-microservices/
├── configserver/         Spring Cloud Config Server (config files in src/main/resources/config/)
├── eureka/               Netflix Eureka server
├── gateway/              Spring Cloud Gateway + KeycloakUserSyncFilter
├── usersevice (1)/       User service (PostgreSQL)
├── activityservice/      Activity service (MongoDB + Kafka producer)
├── aiservice/            AI service (Kafka consumer + Gemini + MongoDB)
└── fitness-frontend/     React + Vite SPA
```
