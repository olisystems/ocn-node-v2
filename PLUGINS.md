# OCN Node Plugins

Plugins extend the node with custom HTTP endpoints, custom OCPI modules, and OCPI hooks. They are **Spring Boot auto-configurations** loaded from JARs on `loader.path` at JVM startup (load once, run for the lifetime of the process).

## Quick start

1. Build a plugin in its own repo (example):
   ```bash
   cd ../ocn-node-plugins/example-plugin
   ./gradlew clean jar
   ```
2. Copy the JAR into the node plugin directory:
   ```bash
   cd ../ocn-node-v2
   mkdir -p plugins
   cp ../ocn-node-plugins/example-plugin/build/libs/*.jar plugins/
   ```
3. Start the node (`loader.path` defaults to `./plugins` for `bootRun` and Docker):
   ```bash
   ./gradlew bootRun
   ```
   Or:
   ```bash
   java -Dloader.path=plugins -jar build/libs/node-ocn-v2.jar
   ```
4. Call a plugin HTTP route (path depends on the plugin):
   ```bash
   curl http://localhost:8080/ocn-v2/plugin/hello
   ```

Plugin configuration (env / properties) belongs with each plugin — the node only provides `loader.path` and the plugin SPI.
## Creating a plugin

### 1. Auto-configuration entry point

Add a configuration class and register it for Boot:

**`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`**

```text
com.example.myplugin.MyPluginAutoConfiguration
```

**`MyPluginAutoConfiguration.kt`**

```kotlin
@Configuration
@ComponentScan(basePackageClasses = [MyPluginAutoConfiguration::class])
class MyPluginAutoConfiguration
```

Boot discovers this when the plugin JAR is on `loader.path`.

### 2. Custom HTTP routes

Use a normal `@RestController` under `${ocn.node.apiPrefix}/plugin`:

```kotlin
@RestController
@RequestMapping("\${ocn.node.apiPrefix}/plugin")
class MyController {
    @GetMapping("/my-resource")
    fun handle() = ResponseEntity.ok("...")
}
```

### 3. Custom OCPI modules

Implement `CustomModule` as a Spring `@Component`:

```kotlin
@Component
class MyCustomModule : CustomModule {
    override fun moduleId() = "mymodule"
    override fun handle(request: CustomModuleRequest) =
        CustomModuleResponse(statusCode = 1000, statusMessage = "Success", data = mapOf("ok" to true))
}
```

`CustomModuleResponse.statusCode` is an **OCPI** status code (e.g. `1000`), not an HTTP status. The node always returns HTTP 200 for plugin-handled custom modules and puts the OCPI status in the response body.

Incoming requests are authenticated the same way as forwarded OCPI traffic (known sender token/role, whitelist for local receivers, and OCN signature checks when enabled) before the handler runs.

Requests to `{apiPrefix}/ocpi/custom/{sender|receiver}/mymodule[/...]` are handled locally when a bean is present; otherwise the node forwards as before.

### 4. OCPI object events (CDR tap, etc.)

Listen with Spring events:

```kotlin
@Component
class MyListener(private val service: MyService) {
    @EventListener
    fun onOcpiObject(event: OcpiObjectEvent) { ... }
}
```

The node publishes `OcpiObjectEvent` on the application event bus for `REQUEST_BODY` and `RESPONSE_DATA` phases.

### 5. Protocol adapters and version contributors

Provide Spring beans implementing `OcpiProtocolAdapter` or `OcpiVersionContributor`; the node injects `List<...>` of all implementations.

### 6. Build a thin JAR

The plugin JAR must **not** bundle the node or Spring Boot. It should contain:

- Your plugin classes
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Dependencies your plugin needs (except those provided by the node at runtime)

Compile against the node API:

```kotlin
compileOnly("snc.openchargingnetwork:node:ocn-v2")
```

## Configuration

| Property / env | Default | Description |
|----------------|---------|-------------|
| `loader.path` / `-Dloader.path` | `plugins` (via `bootRun` / Docker) | Classpath directories or JARs for plugins |
| `ocn.plugins.loader-path` | `plugins` | Documented default path (informational; use `loader.path` at JVM startup) |
| `OCN_PLUGINS_LOADER_PATH` | `/app/plugins` (Docker) | Directory passed to `-Dloader.path` |
| `OCN_PLUGINS` | _(empty)_ | Comma-separated plugin ids to download before JVM start (e.g. `edx_v1,other_v2` → objects `edx_v1.jar`, `other_v2.jar`) |
| `OTC_BUCKET_NAME` | — | OBS bucket that holds plugin JARs (e.g. `ocn-node-plugins`; required when `OCN_PLUGINS` is set) |
| `OTC_ACCESS_KEY_ID` / `OTC_SECRET_ACCESS_KEY` | — | OBS credentials (required when `OCN_PLUGINS` is set) |
| `OTC_ENDPOINT_URL` | `https://obs.eu-de.otc.t-systems.com` | S3-compatible OBS endpoint |
| `OTC_DEFAULT_REGION` | `eu-de` | OBS region |

There is no runtime reload: change JARs in the bucket (or under `plugins/` locally) and **restart** the process.

### Cluster

The Docker entrypoint fetches each key in `OCN_PLUGINS` from OTC OBS into `/app/plugins`, then starts the JVM. No plugins PVC is required — upload JARs to the bucket manually and rollout-restart the deployment.

## Operations and troubleshooting

### Class not found

Plugin JAR references a class not on the node classpath or in the plugin JAR. Add the dependency to the plugin JAR or use a node-provided type.

### Plugin not active after restart

- JAR is under the directory passed to `loader.path`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lists your `@Configuration` class
- Check startup logs under **PLUGINS** in the node banner

### Plugin HTTP routes return 404 (`/plugin/...`)

The route exists in the plugin JAR, but Spring never registered the `@RestController`. Common causes:

1. **`loader.path` not applied** — only `PropertiesLauncher` (fat JAR + `-Dloader.path=plugins`) or `bootRun` with plugin JARs on the classpath loads plugins. Running `ApplicationKt` from the IDE without plugin JARs on the classpath gives 404.
2. **DevTools restart** — a restarted dev process can drop `PropertiesLauncher` and ignore `loader.path`. `bootRun` disables restart; restart the node after changing plugins.
3. **Plugin config missing** — some plugins only register controllers when required properties/env are set (see that plugin’s README).

Verify after restart with the plugin’s documented health/list route.
### Custom module not invoked

- A `@Component` implements `CustomModule` with matching `moduleId()`
- First registration wins if two beans use the same id

### Event listener not called

- Listener uses `@EventListener` on `OcpiObjectEvent`
- Filter by `event.module`, `event.phase`, and payload type as needed
