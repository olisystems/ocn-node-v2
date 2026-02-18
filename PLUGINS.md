# OCN Node Plugins

Plugins extend the node with custom non-OCPI HTTP endpoints and custom OCPI modules. They are loaded at startup from a configurable directory.

## Quick start

1. Build the external example plugin:
   ```bash
   cd ../example-plugin
   ./gradlew clean jar
   ```
2. Copy the built plugin JAR into the node plugin directory:
   ```bash
   cd ../ocn-node-v2
   mkdir -p plugins
   cp ../example-plugin/build/libs/ocn-node-example-plugin-*.jar plugins/
   ```
3. Start the node (default `plugins` dir is relative to CWD):
   ```bash
   java -jar build/libs/ocn-node-*.jar
   ```
4. Call the example plugin endpoint (replace `ocn-v2` if you use another API prefix):
   ```bash
   curl http://localhost:8080/ocn-v2/plugins/hello
   ```
5. Call the example custom OCPI module (requires OCPI headers; for a quick check use the node’s custom module path):
   - Endpoint pattern: `{apiPrefix}/ocpi/custom/{sender|receiver}/example[/...]`

## Creating a plugin

### 1. Implement the entry point

Implement `snc.openchargingnetwork.node.plugins.core.NodePlugin`:

- `id()`: unique plugin ID (used for logging and collision checks)
- `version()`: version string
- `init(context: PluginContext)`: called once at startup; use `context` to register endpoints and modules

### 2. Register endpoints (non-OCPI)

Use `context.endpointRegistry().register(pluginId, path, method, handler)`:

- `path`: path under the plugin base, e.g. `"/my-resource"` (leading slash; normalized by the registry)
- `method`: `HttpMethod.GET`, `POST`, etc.
- `handler`: `PluginEndpointHandler` that receives `PluginEndpointRequest` (path, method, queryParams, headers, body) and returns `PluginEndpointResponse` (statusCode, body?, contentType?)

Requests are served at `{apiPrefix}/plugins{path}`.

### 3. Register custom OCPI modules

Use `context.customModuleRegistry().register(pluginId, customModuleId, handler)`:

- `customModuleId`: module name (e.g. `"mymodule"`); first registration wins if multiple plugins use the same ID
- `handler`: `CustomModuleHandler` that receives `CustomModuleRequest` (interfaceRole, urlPath, method, queryParams, body, from/to party/country, headers) and returns `CustomModuleResponse` (statusCode, statusMessage?, data?)

Incoming requests to `{apiPrefix}/ocpi/custom/{sender|receiver}/{customModuleId}[/...]` are routed to the registered handler when present; otherwise the node forwards as before.

### 4. SPI registration

Add a file in your JAR:

**Path:** `META-INF/services/snc.openchargingnetwork.node.plugins.core.NodePlugin`

**Content:** one line with the fully qualified class name of your implementation, e.g.:

```
com.example.myplugin.MyNodePlugin
```

### 5. Build a thin JAR

The plugin JAR must **not** bundle the node or Spring. It should contain only:

- Your plugin classes
- `META-INF/services/...NodePlugin`
- Any dependencies your plugin code needs (except those provided by the node at runtime)

At runtime the node loads the JAR with a classloader whose parent is the application classloader, so the plugin sees the same `NodePlugin`, `PluginContext`, and Spring types as the node.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `ocn.plugins.dir` | `plugins` | Directory containing plugin JARs (absolute or relative to process CWD). |
| `ocn.plugins.failOnLoadError` | `true` | If `true`, the node fails to start when any plugin fails to load or init. If `false`, failed plugins are skipped and logged. |
| `ocn.plugins.initTimeoutMs` | `30000` | Timeout in ms for each plugin’s `init(context)` call. |

Environment variables (with defaults): `OCN_PLUGINS_DIR`, `OCN_PLUGINS_FAIL_ON_LOAD_ERROR`, `OCN_PLUGINS_INIT_TIMEOUT_MS`.

## Operations and troubleshooting

### Classloading errors

- **Symptom:** `NoClassDefFoundError` or `ClassNotFoundException` when loading a plugin.
- **Cause:** The plugin JAR (or something it loads) references a class that is not visible to the plugin classloader (e.g. a dependency that is not on the node’s classpath and not in the plugin JAR).
- **Fix:** Add the missing dependency to the plugin JAR, or ensure the node provides it on its classpath. Do not bundle the node or Spring in the plugin JAR.

### Duplicate plugin ID

- **Symptom:** Log message about duplicate plugin ID; one of the plugins is not loaded.
- **Cause:** Two JARs (or two SPI entries in one JAR) declare the same `id()`.
- **Fix:** Use a unique plugin ID per plugin (e.g. include organisation or product name).

### Endpoint path collision

- **Symptom:** Two plugins register the same path and method; one overwrites the other (first wins).
- **Fix:** Use distinct paths per plugin (e.g. prefix with plugin ID: `/myplugin/resource`).

### Module ID collision

- **Behaviour:** First plugin to register a given `customModuleId` wins; later registrations for the same ID are ignored.
- **Fix:** Use a unique custom module ID per plugin or coordinate with other plugins.

### Plugin init timeout

- **Symptom:** Startup fails or a plugin is skipped with a timeout error.
- **Cause:** `init(context)` took longer than `ocn.plugins.initTimeoutMs`.
- **Fix:** Reduce work in `init()` or increase `ocn.plugins.initTimeoutMs`. Do not block indefinitely (e.g. on network or DB) in `init()`.

### Startup summary

At startup the node logs how many plugins were loaded and how many failed. With `ocn.plugins.failOnLoadError=false`, the node can start even if some plugins fail; check logs for “Plugin failure” and the reported source/reason.
