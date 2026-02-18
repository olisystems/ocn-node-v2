# Open Charging Network Node (v2 fork)

OCN Node is a Kotlin + Spring Boot service that brokers OCPI traffic between parties and integrates with the OCN Registry.

This repository is a maintained fork of the original OCN Node with updated dependencies, Java 21 support, and an external plugin architecture.

## What is in this fork

- OCPI v2.2 oriented node implementation
- Registry/indexer integration used by this deployment
- Plugin system for:
  - custom non-OCPI HTTP endpoints
  - custom OCPI modules
- Gradle Kotlin DSL build and test tasks

## Requirements

- Java 21 (JDK)
- Docker (optional, for integration-test dependencies)
- PostgreSQL (or use profile/test defaults depending on your setup)

## Quick start (local development)

From `ocn-node-v2`:

```bash
./gradlew clean build
./gradlew bootRun --args='--spring.profiles.active=local'
```

The node starts on `http://localhost:8080` by default (see profile and env overrides below).

Health check:

```bash
curl http://localhost:8080/actuator/health
```

## Build

```bash
./gradlew clean build
```

Artifacts are generated in `build/libs/`.

## Run

### Default run

```bash
./gradlew bootRun
```

### Run with profile

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

There are also convenience tasks in `build.gradle.kts`:

- `bootRunDev`
- `bootRunTest`
- `bootRunLocalMiniKube`

## Configuration

Base configuration is in:

- `src/main/resources/application.properties`

Profile-specific overrides currently available:

- `src/main/resources/application-local.properties`
- `src/main/resources/application-test.properties`

The project is primarily configured through environment variables. Common ones:

- `OCN_NODE_URL`
- `OCN_NODE_ADMIN_KEY`
- `OCN_NODE_PRIVATE_KEY`
- `OCN_NODE_API`
- `OCN_NODE_COUNTRY_CODE`
- `OCN_NODE_PARTY_ID`
- `OCN_REGISTRY_INDEXER_URL`
- `OCN_REGISTRY_INDEXER_TOKEN`
- `OCN_NODE_POSTGRES_DATABASE`
- `OCN_NODE_POSTGRES_USER`
- `OCN_NODE_POSTGRES_PASSWORD`

Plugin runtime configuration:

- `OCN_PLUGINS_DIR` (default: `plugins`)
- `OCN_PLUGINS_FAIL_ON_LOAD_ERROR` (default: `true`)
- `OCN_PLUGINS_INIT_TIMEOUT_MS` (default: `30000`)

## Subgraph dependency

This node relies on an indexed OCN Registry subgraph (GraphQL) for registry data at runtime.

- Config keys:
  - `OCN_REGISTRY_INDEXER_URL`
  - `OCN_REGISTRY_INDEXER_TOKEN`
- The node queries the indexer for parties/operators and verification data, then caches the registry snapshot in memory.
- Registry-dependent flows include:
  - party and operator discovery/routing
  - hub client info pull/sync operations
  - OCN signature verification against operator addresses
  - admin/manual registry refresh operations

Operational impact if indexer access is unavailable or misconfigured:

- registry refresh calls fail
- registry-dependent endpoints/services may fail or return stale data from cache
- cross-node resolution and related checks can degrade

Recommended operations guidance:

- monitor indexer availability and token validity
- treat indexer URL/token as required configuration in non-local environments
- verify registry connectivity during deployment/health checks

## Plugins

Plugin support is fully documented in:

- [PLUGINS.md](./PLUGINS.md)


## Testing

Run unit tests:

```bash
./gradlew unitTest
```

Run integration tests:

```bash
./gradlew integrationTest
```

Run both (integration tests also run automatically in CI):

```bash
./gradlew test
```

## Project structure (high level)

- `src/main/kotlin` - application source
- `src/test/kotlin` - unit and integration tests
- `src/main/resources` - Spring config files
- `infra` - deployment helpers (for example systemd service file)

## Notes

- This README intentionally focuses on the current fork and local developer workflow.
- For plugin authoring, SPI setup, collisions, and troubleshooting, use `PLUGINS.md`.
