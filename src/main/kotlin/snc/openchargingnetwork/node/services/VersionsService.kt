package snc.openchargingnetwork.node.services

import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository


@Service
class VersionsService(
    private val roleRepo: RoleRepository,
    private val platformRepo: PlatformRepository,
    private val httpClientComponent: HttpClientComponent
) {
    fun getPartyVersions(toCountryCode: String, toPartyID: String): Pair<Boolean, List<Version>> {
        val platform = this.getLocalPlatform(toCountryCode, toPartyID);
        if(platform !== null) {
            return true to httpClientComponent.getVersions(platform.versionsUrl!!, platform.auth.tokenB!!);
        }
        return false to listOf();
    }

    private fun getLocalPlatform(countryCode: String, partyID: String): PlatformEntity? {
        val role = roleRepo.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(countryCode, partyID);
        if (role == null) return null;
        val platform = platformRepo.findById(role.platformID).get();
        if (platform == null) return null;
        return platform;
    }
}