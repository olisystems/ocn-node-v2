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

import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
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

    /** Reads the platform as it is stored now and holds its row until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlatformEntity p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): PlatformEntity?
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

private const val PENDING = "snc.openchargingnetwork.node.models.entities.IntegrationCopyStatus.PENDING"
private const val DELIVERED = "snc.openchargingnetwork.node.models.entities.IntegrationCopyStatus.DELIVERED"

interface IntegrationCopyOutboxRepository : JpaRepository<IntegrationCopyOutboxEntity, Long> {

    /**
     * Rows that may be sent now, oldest first. A row waits while an older row for the same
     * target, module and object is still pending, which keeps writes to one object in order.
     */
    @Query(
            """
            select o.id from IntegrationCopyOutboxEntity o
            where o.status = $PENDING
              and o.nextAttemptAt <= :now
              and (o.lockedUntil is null or o.lockedUntil < :now)
              and not exists (
                  select 1 from IntegrationCopyOutboxEntity p
                  where p.status = $PENDING
                    and p.id < o.id
                    and p.targetCountry = o.targetCountry
                    and p.targetParty = o.targetParty
                    and p.module = o.module
                    and p.objectKey = o.objectKey
              )
            order by o.id
            """
    )
    fun findDueIds(@Param("now") now: Instant, pageable: Pageable): List<Long>

    /**
     * Takes the row for this replica. Returns 0 when another replica got there first: the
     * database re-checks the condition after waiting for that replica's update. That includes a
     * replica that already sent the row, failed and set its backoff.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            """
            update IntegrationCopyOutboxEntity o
            set o.lockedUntil = :lockedUntil, o.attempts = o.attempts + 1
            where o.id = :id
              and o.status = $PENDING
              and o.nextAttemptAt <= :now
              and (o.lockedUntil is null or o.lockedUntil < :now)
            """
    )
    fun claim(
            @Param("id") id: Long,
            @Param("now") now: Instant,
            @Param("lockedUntil") lockedUntil: Instant
    ): Int

    /**
     * Stores the result of a send and gives the row back. Returns 0 when the row is no longer
     * held under [lease]: the send outlasted it and another replica took the row over, so that
     * replica's result is the one to keep.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            """
            update IntegrationCopyOutboxEntity o
            set o.status = :status, o.deliveredAt = :deliveredAt, o.payload = :payload,
                o.nextAttemptAt = :nextAttemptAt, o.lastError = :lastError, o.lockedUntil = null
            where o.id = :id
              and o.lockedUntil = :lease
            """
    )
    fun release(
            @Param("id") id: Long,
            @Param("lease") lease: Instant,
            @Param("status") status: IntegrationCopyStatus,
            @Param("deliveredAt") deliveredAt: Instant?,
            @Param("payload") payload: String?,
            @Param("nextAttemptAt") nextAttemptAt: Instant,
            @Param("lastError") lastError: String?
    ): Int

    @Transactional
    @Modifying
    @Query("delete from IntegrationCopyOutboxEntity o where o.status = $DELIVERED and o.deliveredAt < :cutoff")
    fun deleteDeliveredBefore(@Param("cutoff") cutoff: Instant): Int
}
