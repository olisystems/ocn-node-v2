/*
    Copyright 2026 OLI Systems GmbH

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package snc.openchargingnetwork.node.models.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "ocpi211_adapter_config")
class Ocpi211AdapterConfigEntity(
        @Column(name = "platform_id", nullable = false, unique = true) var platformId: Long,
        @Column(name = "protocol_version", nullable = false) var protocolVersion: String = "2.1.1",
        @Column(name = "credentials_role") var credentialsRole: String? = null,
        @Column(name = "interface_role") var interfaceRole: String? = null,
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "headers", columnDefinition = "json")
        var headers: Map<String, String> = emptyMap(),
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "authorization_tokens", columnDefinition = "json")
        var authorizationTokens: List<String> = emptyList(),
        @Column(name = "mapping_bundle", columnDefinition = "text")
        var mappingBundle: String? = null,
        @Column(name = "mapping_bundle_version") var mappingBundleVersion: String? = null,
        @Column(name = "mapping_imported_at") var mappingImportedAt: Instant? = null,
        @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
        @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
        @Id @GeneratedValue var id: Long? = null
)
