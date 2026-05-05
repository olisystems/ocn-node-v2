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

package snc.openchargingnetwork.node.plugins

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.plugins.core.OcpiObjectEvent
import snc.openchargingnetwork.node.plugins.core.OcpiObjectEventPhase
import snc.openchargingnetwork.node.plugins.core.OcpiObjectEventRegistryImpl

class OcpiObjectEventRegistryImplTest {

    @Test
    fun `publish sends event to registered handlers`() {
        val registry = OcpiObjectEventRegistryImpl()
        val captured = mutableListOf<OcpiObjectEvent>()
        val event = objectEvent()

        registry.register("test") { captured.add(it) }

        registry.publish(event)

        assertThat(captured).containsExactly(event)
        assertThat(registry.listHandlers()).hasSize(1)
    }

    @Test
    fun `unregister removes handler`() {
        val registry = OcpiObjectEventRegistryImpl()
        val captured = mutableListOf<OcpiObjectEvent>()

        registry.register("test") { captured.add(it) }
        registry.unregister("test")
        registry.publish(objectEvent())

        assertThat(captured).isEmpty()
        assertThat(registry.listHandlers()).isEmpty()
    }

    private fun objectEvent(): OcpiObjectEvent =
        OcpiObjectEvent(
            phase = OcpiObjectEventPhase.REQUEST_BODY,
            module = ModuleID.CDRS,
            interfaceRole = InterfaceRole.RECEIVER,
            method = HttpMethod.POST,
            urlPath = null,
            customModuleId = null,
            queryParams = emptyMap(),
            payload = mapOf("id" to "cdr-1"),
            payloadIndex = null,
            fromPartyId = "CPO",
            fromCountryCode = "DE",
            toPartyId = "EMS",
            toCountryCode = "FR",
            headers = emptyMap(),
            responseStatusCode = 200,
            ocpiStatusCode = 1000
        )
}
