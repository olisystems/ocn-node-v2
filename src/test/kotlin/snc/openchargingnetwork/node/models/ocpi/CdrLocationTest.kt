package snc.openchargingnetwork.node.models.ocpi

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CdrLocationTest {

    // Same leniency as the node: unknown properties are dropped without an error.
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun cdrLocationJson(stateProperty: String) = """
        {
          "id": "LOC1",
          "address": "Rathausstrasse 1",
          "city": "Stuttgart",
          "postal_code": "70176",
          $stateProperty
          "country": "DEU",
          "coordinates": {"latitude": "48.7758", "longitude": "9.1829"},
          "evse_uid": "CP1*1",
          "evse_id": "DE*ABC*E1*1",
          "connector_id": "1",
          "connector_standard": "IEC_62196_T2",
          "connector_format": "SOCKET",
          "connector_power_type": "AC_3_PHASE"
        }
    """

    @Test
    fun `state is kept when a CDR location is forwarded`() {
        val location: CdrLocation = mapper.readValue(cdrLocationJson(""""state": "BW","""))

        assertEquals("BW", location.state)
        assertEquals("BW", mapper.readTree(mapper.writeValueAsString(location))["state"].asText())
    }

    @Test
    fun `state stays absent when the CPO did not send one`() {
        val location: CdrLocation = mapper.readValue(cdrLocationJson(""))

        assertFalse(mapper.readTree(mapper.writeValueAsString(location)).has("state"))
    }
}
