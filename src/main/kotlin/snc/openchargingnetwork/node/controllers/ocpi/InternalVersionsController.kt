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

package snc.openchargingnetwork.node.controllers.ocpi

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snc.openchargingnetwork.node.components.OcpiPlatformAuthService
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.ocpi.Endpoint
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.models.ocpi.VersionDetail
import snc.openchargingnetwork.node.plugins.core.OcpiVersionContributor
import snc.openchargingnetwork.node.tools.urlJoin

@RestController
@RequestMapping("\${ocn.node.apiPrefix}\${ocn.node.apiPrefixPublic}/ocpi")
class InternalVersionsController(
        private val platformAuthService: OcpiPlatformAuthService,
        private val versionContributors: List<OcpiVersionContributor>,
        private val properties: NodeProperties
) {

    @GetMapping("/versions")
    fun getVersions(
            @RequestHeader("Authorization") authorization: String
    ): OcpiResponse<List<Version>> {

        platformAuthService.assertTokenAOrC(authorization)
        val endpoint2_2_1 = urlJoin(properties.url, properties.apiPrefix, properties.apiPrefixPublic, "/ocpi/2.2.1")
        val versions =
                mutableListOf(Version("2.2.1", endpoint2_2_1))
        versions.addAll(versionContributors.flatMap { it.versions() })
        return OcpiResponse(OcpiStatus.SUCCESS.code, data = versions.distinctBy { it.version })
    }

    @GetMapping("/2.2")
    fun getVersionsDetail(
            @RequestHeader("Authorization") authorization: String
    ): OcpiResponse<VersionDetail> {

        platformAuthService.assertTokenAOrC(authorization)
        val endpoints = this.getAllEndpoints()
        return OcpiResponse(OcpiStatus.SUCCESS.code, data = VersionDetail("2.2", endpoints))
    }

    @GetMapping("/2.2.1")
    fun getVersionsDetail2_2_1(
            @RequestHeader("Authorization") authorization: String
    ): OcpiResponse<VersionDetail> {

        platformAuthService.assertTokenAOrC(authorization)
        val endpoints = this.getAllEndpoints()
        return OcpiResponse(OcpiStatus.SUCCESS.code, data = VersionDetail("2.2.1", endpoints))
    }

    private fun getModuleEndpoints(module: ModuleID): List<Endpoint> {
        return InterfaceRole.values().map {
            val paths =
                    if (module == ModuleID.CUSTOM) {
                        "/ocpi/custom/${it.id}"
                    } else {
                        "/ocpi/${it.id}/2.2.1/${module.id}"
                    }
            Endpoint(
                    identifier = module.id,
                    role = it,
                    url = urlJoin(properties.url, properties.apiPrefix, properties.apiPrefixPublic, paths)
            )
        }
    }

    private fun getAllEndpoints(): List<Endpoint> {
        val endpoints = mutableListOf<Endpoint>()
        val senderOnlyInterfaces = listOf(ModuleID.CREDENTIALS, ModuleID.HUB_CLIENT_INFO)

        for (module in ModuleID.values()) {
            if (module == ModuleID.CUSTOM || module == ModuleID.VERSIONS) {
                continue
            }
            if (senderOnlyInterfaces.contains(module)) {
                // these modules have only SENDER endpoint (the node/hub)
                endpoints.add(
                        Endpoint(
                                identifier = module.id,
                                role = InterfaceRole.SENDER,
                                url =
                                        urlJoin(
                                                properties.url,
                                                properties.apiPrefix,
                                                properties.apiPrefixPublic,
                                                "/ocpi/2.2.1/${module.id}"
                                        )
                        )
                )
            } else {
                endpoints.addAll(getModuleEndpoints(module))
            }
        }

        return endpoints
    }
}
