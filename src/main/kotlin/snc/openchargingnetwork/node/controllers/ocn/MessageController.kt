/*
    Copyright 2019-2020 eMobility GmbH

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

package snc.openchargingnetwork.node.controllers.ocn

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.VersionsController
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.services.VersionsService


@RestController
@RequestMapping("\${ocn.node.apiPrefix}/ocn/message")
class MessageController(
    private val requestHandlerBuilder: OcpiRequestHandlerBuilder,
    private val versionsService: VersionsService
) {

    @PostMapping
    fun postMessage(
        @RequestHeader("X-Request-ID") requestID: String,
        @RequestHeader("OCN-Signature") signature: String,
        @RequestBody body: String
    ): ResponseEntity<OcpiResponse<Any>> {
        val ocpiRequest = requestHandlerBuilder
            .build<Any>(body);

        if(ocpiRequest.request.resolveModuleId() == "versions") {
            val receiver = ocpiRequest.request.headers.receiver;
            val (_,versions) = versionsService.getPartyVersions(receiver.country, receiver.id);

            return ResponseEntity.ok(
                OcpiResponse(
                    statusCode = OcpiStatus.SUCCESS.code,
                    data = versions
                )
            )
        }

        return ocpiRequest
            .forwardFromOcn(signature)
            .getResponseWithAllHeaders()
    }

}