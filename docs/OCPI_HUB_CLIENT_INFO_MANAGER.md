# Enhanced OCPI Hub Client Info Service

## Overview

The `HubClientInfoService` has been enhanced to include comprehensive OCPI Hub Client Info module management with both **push** and **pull** models for party discovery and updates. This enhanced service consolidates all hub client info functionality into a single, unified service that provides complete party information management across the OCN network.

## Features

### Pull Model Operations
- **Registry Party Discovery**: Automatically checks the OCN registry indexer for new parties and roles
- **Role Update Detection**: Monitors existing parties for role changes and updates
- **Async Processing**: All pull operations are performed asynchronously to avoid blocking

### Push Model Operations
- **Party Broadcasting**: Sends hub client info updates to all connected parties
- **Node Broadcasting**: Propagates changes to other OCN nodes in the network
- **Selective Notifications**: Only notifies parties that have implemented the HubClientInfo receiver endpoint

### Comprehensive Management
- **Unified Sync**: Combines pull and push operations in a single comprehensive sync
- **Error Handling**: Robust error handling with detailed logging
- **Admin Interface**: REST endpoints for manual triggering and monitoring
- **Backward Compatibility**: Maintains all existing functionality

## Architecture

### Service Components

1. **HubClientInfoService**: Enhanced main service class with all business logic (existing + new)
2. **OcpiHubClientInfoSyncTask**: Scheduled task for automatic synchronization (replaces PlannedPartySearch)
3. **HubClientInfoManagementController**: Admin REST controller for manual operations

### Dependencies

- `HttpClientComponent`: For making HTTP requests to registry and other nodes
- `NetworkClientInfoRepository`: Database operations for client info
- `PlatformRepository`: Platform management
- `RoleRepository`: Role management
- `EndpointRepository`: Endpoint discovery
- `RoutingService`: Request routing
- `WalletService`: Digital signature operations
- `OcnRulesService`: Whitelist checking
- `RegistryService`: Registry operations
- `RegistryIndexerProperties`: Registry indexer configuration

## Scheduled Tasks

### OcpiHubClientInfoSyncTask (Replaces PlannedPartySearch)

The `OcpiHubClientInfoSyncTask` is a comprehensive scheduled task that **replaces** the old `PlannedPartySearch` task with enhanced functionality:

#### **What it does:**
- **PULL Operations**: Discovers new parties and role updates from registry
- **PUSH Operations**: Broadcasts changes to connected parties and other OCN nodes
- **Comprehensive Sync**: Performs both pull and push operations in sequence

#### **Scheduling:**
- **Frequency**: Every 1 hour (configurable via `HUB_CLIENT_INFO_SYNC_RATE`)
- **Configuration**: Controlled by `ocn.node.hubClientInfoSyncEnabled` or `ocn.node.plannedPartySearchEnabled`

#### **Advantages over PlannedPartySearch:**
- ✅ **Enhanced Functionality**: Does everything PlannedPartySearch did PLUS more
- ✅ **Push Model**: Broadcasts changes to parties and nodes
- ✅ **Role Updates**: Detects and processes role changes
- ✅ **Better Architecture**: Uses service layer instead of direct dependencies
- ✅ **Improved Logging**: More detailed logging and error handling
- ✅ **Backward Compatible**: Can be enabled with existing configuration

## API Endpoints

### Admin Endpoints

All admin endpoints are available under `/ocn-v2/admin/hub-client-info/`

#### 1. Comprehensive Sync
```http
POST /ocn-v2/admin/hub-client-info/sync
```
Triggers a complete hub client info synchronization (pull + push)

#### 2. Check New Parties
```http
POST /ocn-v2/admin/hub-client-info/check-new-parties
```
Manually check for new parties from the registry (pull operation)

#### 3. Check Role Updates
```http
POST /ocn-v2/admin/hub-client-info/check-role-updates
```
Manually check for role updates in existing parties (pull operation)

#### 4. Broadcast Client Info
```http
POST /ocn-v2/admin/hub-client-info/broadcast
Content-Type: application/json

{
  "party_id": "EXAMPLE",
  "country_code": "DE",
  "role": "CPO",
  "status": "PLANNED",
  "last_updated": "2024-01-01T12:00:00Z"
}
```
Manually broadcast hub client info to all parties and nodes (push operation)

#### 5. Renew Connection
```http
POST /ocn-v2/admin/hub-client-info/renew-connection?partyId=EXAMPLE&countryCode=DE
```
Renew client connection for a specific party

## Configuration

### Properties

The service uses the following configuration properties:

```properties
# OCN Registry Indexer
ocn.registry.indexer.url=https://gateway.thegraph.com/api/subgraphs/id/...
ocn.registry.indexer.token=your-token-here

# OCN Node
ocn.node.url=http://localhost:8080
ocn.node.apiPrefix=ocn-v2
ocn.node.privatekey=your-private-key-here

# Scheduled Tasks
ocn.node.hubClientInfoSyncEnabled=true  # Enable enhanced sync task
ocn.node.plannedPartySearchEnabled=true # Legacy flag (still works)
```

### Scheduling

The scheduled task runs every hour by default:

```properties
# Custom scheduling (if needed)
hub.client.info.sync.rate=3600000  # 1 hour in milliseconds
```

## Migration from Previous Version

### What Changed

1. **Enhanced Functionality**: Added pull model operations and comprehensive sync
2. **New Methods**: Added methods for registry discovery and role updates
3. **Improved Logging**: Enhanced logging throughout the service
4. **Admin Interface**: New REST endpoints for manual operations
5. **Task Consolidation**: `PlannedPartySearch` replaced with `OcpiHubClientInfoSyncTask`

### What Remains the Same

1. **Existing Methods**: All existing methods remain unchanged and backward compatible
2. **API Contracts**: Existing API contracts are preserved
3. **Dependencies**: Core dependencies remain the same (added RegistryIndexerProperties)
4. **Database Operations**: All existing database operations work as before
5. **Configuration**: Existing `plannedPartySearchEnabled` flag still works

### Migration Steps

1. **No Breaking Changes**: The service is backward compatible
2. **Optional New Features**: New functionality can be adopted gradually
3. **Existing Code**: All existing code using HubClientInfoService continues to work
4. **New Features**: New features are available when needed
5. **Task Replacement**: `PlannedPartySearch` is automatically replaced with enhanced functionality

## Performance Considerations

### Async Operations

- All registry queries are performed asynchronously
- Broadcasting operations are non-blocking
- Database operations use appropriate transaction boundaries

### Scalability

- The service can handle large numbers of parties efficiently
- Broadcasting is optimized to avoid redundant notifications
- Database queries are optimized with proper indexing

### Monitoring

- Comprehensive logging for monitoring and debugging
- Admin endpoints for manual intervention
- Error tracking and reporting

## Security

### Authentication

- Admin endpoints require proper authentication
- All operations respect OCN rules and whitelisting
- Digital signatures for inter-node communication

### Authorization

- Whitelist checking for all operations
- Role-based access control
- Secure token handling

## Testing

### Unit Tests

```kotlin
@ExtendWith(MockitoExtension::class)
class HubClientInfoServiceTest {
    
    @Mock
    private lateinit var httpClientComponent: HttpClientComponent
    
    @Mock
    private lateinit var networkClientInfoRepo: NetworkClientInfoRepository
    
    @InjectMocks
    private lateinit var service: HubClientInfoService
    
    @Test
    fun `should discover new parties from registry`() {
        // Test implementation
    }
    
    @Test
    fun `should broadcast client info to parties`() {
        // Test implementation
    }
    
    @Test
    fun `should maintain backward compatibility`() {
        // Test existing functionality still works
    }
}
```

### Integration Tests

```kotlin
@SpringBootTest
class HubClientInfoServiceIntegrationTest {
    
    @Autowired
    private lateinit var service: HubClientInfoService
    
    @Test
    fun `should perform complete sync cycle`() {
        // Integration test implementation
    }
}
```

## Troubleshooting

### Common Issues

1. **Registry Connection Failures**
   - Check network connectivity
   - Verify registry indexer URL and token
   - Check firewall settings

2. **Broadcasting Failures**
   - Verify party endpoints are accessible
   - Check OCN rules and whitelisting
   - Review authentication tokens

3. **Database Issues**
   - Check database connectivity
   - Verify table structure
   - Review transaction logs

### Debug Mode

Enable debug logging for detailed troubleshooting:

```properties
logging.level.snc.openchargingnetwork.node.services.HubClientInfoService=DEBUG
logging.level.snc.openchargingnetwork.node.scheduledTasks.OcpiHubClientInfoSyncTask=DEBUG
```

## Future Enhancements

### Planned Features

1. **Metrics and Monitoring**: Prometheus metrics integration
2. **Caching**: Redis-based caching for improved performance
3. **Batch Operations**: Batch processing for large datasets
4. **Webhook Support**: Webhook notifications for external systems
5. **Advanced Filtering**: More sophisticated party filtering options

### API Extensions

1. **Bulk Operations**: Batch API endpoints
2. **Filtering**: Query parameters for filtering results
3. **Pagination**: Support for large result sets
4. **WebSocket**: Real-time updates via WebSocket

## Contributing

When contributing to this service:

1. Follow the existing code style and patterns
2. Add comprehensive tests for new functionality
3. Update documentation for any API changes
4. Ensure backward compatibility when possible
5. Add appropriate logging and error handling 