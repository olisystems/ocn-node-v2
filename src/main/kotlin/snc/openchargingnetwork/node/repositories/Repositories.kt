/*
    Copyright 2019-2020 eMobilify GmbH

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

package snc.openchargingnetwork.node.repositories

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.CrudRepository
import snc.openchargingnetwork.node.models.entities.*
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.Role

interface PlatformRepository : CrudRepository<PlatformEntity, Long> {
    fun existsByAuth_TokenA(tokenA: String?): Boolean
    fun existsByAuth_TokenB(tokenB: String?): Boolean
    fun existsByAuth_TokenC(tokenC: String?): Boolean
    fun findByAuth_TokenA(tokenA: String?): PlatformEntity?
    fun findByAuth_TokenB(tokenB: String?): PlatformEntity?
    fun findByAuth_TokenC(tokenC: String?): PlatformEntity?
    fun findByStatusIn(connectionStatusList: List<ConnectionStatus>): Iterable<PlatformEntity>
}

interface RoleRepository : JpaRepository<RoleEntity, Long> {
    // used in registration to prevent multiple roles of the same country_code/party_id combination
    fun existsByCountryCodeAndPartyIDAllIgnoreCase(countryCode: String, partyID: String): Boolean

    // used to ensure the sender's role is registered to a platform on the broker (hub)
    fun existsByPlatformIDAndCountryCodeAndPartyIDAllIgnoreCase(
            platformID: Long?,
            countryCode: String,
            partyID: String
    ): Boolean

    // used in routing to find roles registered with broker (hub)
    fun findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(
            countryCode: String,
            partyID: String
    ): RoleEntity?
    fun findAllByCountryCodeAndPartyIDAllIgnoreCase(
            countryCode: String,
            partyID: String
    ): Iterable<RoleEntity>
    fun findAllByPlatformID(platformID: Long?): Iterable<RoleEntity>
    fun deleteByPlatformID(platformID: Long?)

    // used by the hub client info registry sync to skip parties already registered on this node
    fun existsByCountryCodeAndPartyIDAndRoleAllIgnoreCase(
            countryCode: String,
            partyID: String,
            role: Role
    ): Boolean

    // Delete role by composite key (country_code + party_id + role) for deduplication
    fun deleteByCountryCodeAndPartyIDAndRoleAllIgnoreCase(
            countryCode: String,
            partyID: String,
            role: Role
    )
}

interface EndpointRepository : CrudRepository<EndpointEntity, Long> {
    fun findByPlatformID(platformID: Long?): Iterable<EndpointEntity>
    fun findFirstByPlatformIDAndIdentifierAndRoleOrderByIdAsc(
            platformID: Long?,
            identifier: String,
            Role: InterfaceRole
    ): EndpointEntity?

    fun deleteByPlatformID(platformID: Long?)
}

interface ProxyResourceRepository : CrudRepository<ProxyResourceEntity, Long> {
    fun findByIdAndSenderAndReceiver(
            id: Long?,
            sender: BasicRole,
            receiver: BasicRole
    ): ProxyResourceEntity?
    fun findByAlternativeUIDAndSenderAndReceiver(
            alternativeUID: String,
            sender: BasicRole,
            receiver: BasicRole
    ): ProxyResourceEntity?
}

interface OcnRulesListRepository : CrudRepository<OcnRulesListEntity, Long> {
    fun existsByCounterparty(party: BasicRole): Boolean
    fun findAllByPlatformID(platformID: Long?): Iterable<OcnRulesListEntity>
    fun deleteByPlatformID(platformID: Long?)
    fun deleteByPlatformIDAndCounterparty(platformID: Long?, party: BasicRole)
}

interface NetworkClientInfoRepository : CrudRepository<NetworkClientInfoEntity, Long> {
    fun existsByPartyAndRole(party: BasicRole, role: Role): Boolean
    fun findByPartyAndRole(party: BasicRole, role: Role): NetworkClientInfoEntity?
    fun findFirstByPartyAndRoleOrderByIdAsc(party: BasicRole, role: Role): NetworkClientInfoEntity?
    fun deleteByPartyAndRole(party: BasicRole, role: Role)
}

interface Ocpi211AdapterConfigRepository : CrudRepository<Ocpi211AdapterConfigEntity, Long> {
    fun findByPlatformId(platformId: Long): Ocpi211AdapterConfigEntity?
    fun existsByPlatformId(platformId: Long): Boolean
    fun deleteByPlatformId(platformId: Long)
}
