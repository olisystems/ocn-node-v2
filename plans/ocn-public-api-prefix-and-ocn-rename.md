# OCN Node: `/api/v2/public` route prefix + `ocn-v2` → `ocn` rename

## Context

The OCN Node exposes 77 routes directly at the server root (`/ocpi/**`, `/ocn/**`,
`/admin/**`, `/health`). Every other service in the workspace namespaces its HTTP
surface (`platform-banula` and `platform-whitelabel` both serve OCPI under
`/api/v1/public/**`, internal calls under `/api/v1/internal/**`, and keep `/admin/**`
and `/health` at the root). The node is the odd one out.

Two changes bring it in line:

1. Every route except `/health`, `/swagger-ui`, `/api-docs` and Spring's `/error`
   moves under a new `/api/v2/public` segment.
2. The public path prefix drops the version suffix: `ocn-v2` → `ocn`.

### Why this is not just a find-and-replace

In dev/int/prod the node runs with `OCN_NODE_API=""` and the ingress strips
`/ocn-v2` (`infra/helm/values.yaml:45-46`, `stripPrefix: true`). So `ocn-v2` is an
**ingress** prefix plus the `OCN_NODE_URL` configmap value — not an app-level one.

More importantly, the node advertises its own URLs to counterparties during the
OCPI credentials handshake, built via
`urlJoin(properties.url, properties.apiPrefix, "/ocpi/versions")` at 11 call sites.
If `/api/v2/public` only lands in the `@RequestMapping` annotations, every URL the
node hands out (credentials, version details, pagination `Link:`, `Location:`,
async `response_url`) still points at the old paths and the handshake breaks.
Annotations and self-URL builders must move together.

## Decisions taken

- **Route scope**: all routes get `/api/v2/public` **except** `/health` and
  `/swagger-ui` (and `/api-docs` + `/error`, same class of infra endpoint).
- **Consumer wiring**: `OCN_NODE_URL` keeps meaning "node base URL"
  (`https://…/ocn`). Each consumer gains a separate
  `OCN_NODE_API_PREFIX_PUBLIC` (default `/api/v2/public`) prepended to the moving
  endpoints, leaving `/health` on the bare URL. This mirrors platform-banula's
  existing `party.api-prefix-public` pattern and avoids a `banula-open-library`
  release — platform-banula folds the segment in at `OcnClientConfig.setNodeUrl()`.
- **Jar name unchanged**: `build.gradle.kts:17` stays `ocn-v2`
  (`node-ocn-v2.jar`, `Dockerfile:29`, plugin coordinate). It is a build artifact
  name, not a route.

## Changes by repo

All service repos are on `fix/OLISYS-4927/update_ocn_node_endpoints`;
`general-integration-tests` is on `OLISYS-4927`; `common-infra-resources` is on
`main` (infra repo — edited on the current checkout per `.ai/AGENTS.md`).

### 1. ocn-node-v2

**New property** — `config/NodeProperties.kt`: `var apiPrefixPublic: String = "/api/v2/public"`,
fed by `ocn.node.apiPrefixPublic = ${OCN_NODE_API_PREFIX_PUBLIC:/api/v2/public}` in
`src/main/resources/application.properties`.

**Controllers** (19 files under `controllers/`): class-level
`@RequestMapping("\${ocn.node.apiPrefix}…")` becomes
`@RequestMapping("\${ocn.node.apiPrefix}\${ocn.node.apiPrefixPublic}…")`.
`HealthController.kt` and `OcpiErrorController.kt` are the two exceptions.

**Self-URL builders** — insert `properties.apiPrefixPublic` into the `urlJoin(…)`
chain at every site: `services/CredentialsService.kt:36`,
`controllers/ocpi/InternalVersionsController.kt:49,87,107-111`,
`controllers/admin/AdminController.kt:500`, `controllers/ocn/RegistryController.kt:51`,
`components/OcpiResponseHandler.kt:147-148,173-180`,
`components/OcpiRequestHandler.kt:449,471-476`, `config/Verification.kt:65`,
`config/NodeInfoLogger.kt:59`.

**Servlet filter URL patterns** — `config/TestToolHeaderInjectionFilter.kt:29-30`
and `config/RequestHeaderLoggingFilter.kt:19` (the latter is already broken:
hardcoded and prefix-unaware).

**`ocn-v2` → `ocn`**: `infra/helm/values.yaml:45` ingress prefix,
`docker-compose.yml:49`, `config/OpenAPIConfig.kt:33`, the three
`application*-test.properties`, the `ocn-v2` literals in `src/test/kotlin/**`,
and the `PLUGINS.md` URL examples (the `node:ocn-v2` coordinate stays).

**Configmap var**: add `OCN_NODE_API_PREFIX_PUBLIC: "/api/v2/public"` to
`infra/helm/values.yaml`.

### 2. platform-whitelabel (Go)

`OCNNodeConfig` gains `PublicAPIPath` (`internal/infrastructure/config/config.go:116-122`,
`BindEnv` at :184-188, default `/api/v2/public`). Prepend it in
`internal/infrastructure/http/client/ocn_node_client.go:103,341,398`,
`internal/domain/ocn_communication/credentials_factory.go:104`, and
`internal/application/ocn_connection_service.go:99`. **Leave `ocn_node_client.go:73`
(`/health`) on the bare URL.** Update `local.env`, `.env.RENAME`, `README.md`, and
`infra/helm/values*.yaml`.

### 3. platform-banula (Java)

`ocn-node.api-prefix-public` in `application.yml` + `ApplicationConfiguration.java`,
exposed as a `getOcnNodePublicUrl()` helper. Used at `config/OcnClientConfig.java:79`
(`setNodeUrl`), `client/OcnNodeAdminClient.java:29`, `handler/OutflowHandler.java:60`.
`banula-open-library` is untouched. Update `application-local.yml` and
`infra/helm/values*.yaml`.

### 4. banula-cdr-adapter / banula-billing-service (Java)

Both call `{OCN_NODE_URL}/ocn/registry/node/{cc}/{pid}/certificates`
(`client/OcnNodeClient.java:35`), which moves. Same config-property pattern.
Billing's `client/OcnNodeClient.java:76` (`/health`) stays on the bare URL.
Update `infra/helm/values*.yaml` in both.

### 5. common-infra-resources

`transit/{dev,int,prod}/configmaps/ocn-node-v2-config.yaml`: `OCN_NODE_URL`
`…/ocn-v2` → `…/ocn`, plus a new `OCN_NODE_API_PREFIX_PUBLIC: "/api/v2/public"` key.

### 6. general-integration-tests

`http-clients/ocn-node/client.ts`: prepend `/api/v2/public` to the `/admin/**`
paths, leave `/health`. `ocnNodeApiVersion` `ocn-v2` → `ocn` in
`cypress/config/configuration.{local,dev,int}.ts` (the value is currently dead code).

## Verification

Baseline before any edit (both green): full2 8/8, full3 47/47.

1. `./gradlew compileKotlin` in ocn-node-v2; `go build ./...` in platform-whitelabel;
   `./mvnw compile` in the three Java services.
2. Restart ocn-node-v2, platform-banula, platform-whitelabel (`./stop.sh` then
   `./start.sh`, per `.ai/AGENTS.md`), plus cdr-adapter and billing.
3. Confirm the new surface: `curl localhost:9999/api/v2/public/ocpi/versions` → 200,
   `curl localhost:9999/health` → 200, `curl localhost:9999/ocpi/versions` → 404.
4. Confirm the handshake self-URL: the `url` in `/api/v2/public/ocpi/versions` and in
   the credentials payload must contain `/api/v2/public`.
5. Re-run both specs with `--env profile=local,urls=external,duration=0.5`.
   Both must be green.
