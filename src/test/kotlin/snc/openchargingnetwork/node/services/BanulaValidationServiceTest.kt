package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BanulaValidationServiceTest {
    private val service = BanulaValidationService()

    @Test
    fun `validateCpoTariff should accept valid payload`() {
        val payload =
            """
            {
              "country_code": "DE",
              "party_id": "ALL",
              "id": "17",
              "currency": "EUR",
              "elements": [
                {
                  "price_components": [
                    {
                      "type": "ENERGY",
                      "price": 0.50,
                      "vat": 20.0,
                      "step_size": 1
                    },
                    {
                      "type": "FLAT",
                      "price": 0.50,
                      "vat": 20.0,
                      "step_size": 1
                    }
                  ]
                }
              ],
              "last_updated": "2018-12-17T11:36:01Z",
              "energy_mix": {
                "is_green_energy": true,
                "energy_product_name": "BANULA_CPO_TARIFF"
              }
            }
            """.trimIndent()

        val result = service.validateCpoTariff(payload)

        assertThat(result.valid).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `validateSession should accept valid payload`() {
        val payload =
            """
            {
              "country_code": "BE",
              "party_id": "BEC",
              "id": "101",
              "start_date_time": "2015-06-29T22:39:09Z",
              "kwh": 41.12,
              "cdr_token": {
                "country_code": "NL",
                "party_id": "TST",
                "uid": "123abc",
                "type": "RFID",
                "contract_id": "NL-TST-C12345678-S"
              },
              "auth_method": "WHITELIST",
              "authorization_reference": "REF121212",
              "location_id": "LOC1",
              "evse_uid": "3256",
              "connector_id": "1",
              "currency": "EUR",
              "charging_periods": [
                {
                  "start_date_time": "2015-06-29T22:39:09Z",
                  "dimensions": [
                    {
                      "type": "ENERGY",
                      "volume": 120
                    },
                    {
                      "type": "MAX_CURRENT",
                      "volume": 30
                    }
                  ]
                },
                {
                  "start_date_time": "2015-06-29T22:40:54Z",
                  "dimensions": [
                    {
                      "type": "ENERGY",
                      "volume": 41000
                    },
                    {
                      "type": "MIN_CURRENT",
                      "volume": 34
                    }
                  ]
                },
                {
                  "start_date_time": "2015-06-29T23:07:09Z",
                  "dimensions": [
                    {
                      "type": "PARKING_TIME",
                      "volume": 0.718
                    }
                  ],
                  "tariff_id": "12"
                }
              ],
              "total_cost": {
                "excl_vat": 8.50,
                "incl_vat": 9.35
              },
              "status": "ACTIVE",
              "last_updated": "2015-06-29T23:50:17Z"
            }
            """.trimIndent()

        val result = service.validateSession(payload)

        assertThat(result.valid).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `validateLocation should accept valid payload`() {
        val payload =
            """
            {
              "country_code": "BE",
              "party_id": "BEC",
              "id": "LOC1",
              "publish": true,
              "name": "Gent Zuid",
              "address": "F.Rooseveltlaan 3A",
              "city": "Gent",
              "postal_code": "9000",
              "country": "BEL",
              "coordinates": {
                "latitude": "51.047599",
                "longitude": "3.729944"
              },
              "parking_type": "ON_STREET",
              "evses": [
                {
                  "uid": "3256",
                  "evse_id": "BE*BEC*E041503001",
                  "status": "AVAILABLE",
                  "capabilities": [
                    "RESERVABLE"
                  ],
                  "connectors": [
                    {
                      "id": "1",
                      "standard": "IEC_62196_T2",
                      "format": "CABLE",
                      "power_type": "AC_3_PHASE",
                      "max_voltage": 220,
                      "max_amperage": 16,
                      "max_electric_power": 150000,
                      "tariff_ids": [
                        "11"
                      ],
                      "last_updated": "2015-03-16T10:10:02Z"
                    }
                  ],
                  "physical_reference": "1",
                  "floor_level": "-1",
                  "last_updated": "2015-06-28T08:12:01Z"
                },
                {
                  "uid": "3257",
                  "evse_id": "BE*BEC*E041503002",
                  "status": "RESERVED",
                  "capabilities": [
                    "RESERVABLE"
                  ],
                  "connectors": [
                    {
                      "id": "1",
                      "standard": "IEC_62196_T2",
                      "format": "SOCKET",
                      "power_type": "AC_3_PHASE",
                      "max_voltage": 220,
                      "max_amperage": 16,
                      "max_electric_power": 100000,
                      "tariff_ids": [
                        "12"
                      ],
                      "last_updated": "2015-06-29T20:39:09Z"
                    }
                  ],
                  "physical_reference": "2",
                  "floor_level": "-2",
                  "last_updated": "2015-06-29T20:39:09Z"
                }
              ],
              "energy_mix": {
                "is_green_energy": true,
                "energy_product_name": "BANULA"
              },
              "time_zone": "Europe/Brussels",
              "last_updated": "2015-06-29T20:39:09Z"
            }
            """.trimIndent()

        val result = service.validateLocation(payload)

        assertThat(result.valid).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `validateToken should accept valid payload`() {
        val payload =
            """
            {
              "country_code": "DE",
              "party_id": "TNM",
              "uid": "12345678905880",
              "type": "RFID",
              "contract_id": "DE-TNM-CACC12E4",
              "visual_number": "DF000-2001-8999-1",
              "issuer": "TheNewMotion",
              "valid": true,
              "whitelist": "NEVER",
              "default_profile_type": "GREEN",
              "energy_contract": {
                "supplier_name": "DE*TNM",
                "contract_id": "DE-TNM-CACC12E4"
              },
              "last_updated": "2018-12-10T17:25:10Z"
            }
            """.trimIndent()

        val result = service.validateToken(payload)

        assertThat(result.valid).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `validateCdr should accept valid payload`() {
        val payload =
            """
            {
              "country_code": "DE",
              "party_id": "OLI",
              "id": "12345",
              "start_date_time": "2015-06-29T21:39:09Z",
              "end_date_time": "2015-06-29T23:37:32Z",
              "cdr_token": {
                "country_code": "DE",
                "party_id": "TNM",
                "uid": "012345678",
                "type": "RFID",
                "contract_id": "DE-8ACC12E46L89"
              },
              "authorization_reference": "REF45412",
              "auth_method": "WHITELIST",
              "cdr_location": {
                "id": "LOC1",
                "name": "OLI Office",
                "address": "Silberburgstr. 112",
                "city": "Stuttgart",
                "postal_code": "70176",
                "country": "DEU",
                "coordinates": {
                  "latitude": "3.729944",
                  "longitude": "51.047599"
                },
                "evse_uid": "3256",
                "evse_id": "DE*OLI*E041503003",
                "connector_id": "1",
                "connector_standard": "IEC_62196_T2",
                "connector_format": "SOCKET",
                "connector_power_type": "AC_1_PHASE"
              },
              "currency": "EUR",
              "tariffs": [
                {
                  "country_code": "DE",
                  "party_id": "OLI",
                  "id": "12",
                  "currency": "EUR",
                  "elements": [
                    {
                      "price_components": [
                        {
                          "type": "ENERGY",
                          "price": 2.00,
                          "vat": 10.0,
                          "step_size": 300
                        }
                      ]
                    }
                  ],
                  "last_updated": "2015-02-02T14:15:01Z"
                }
              ],
              "charging_periods": [
                {
                  "start_date_time": "2015-06-29T21:39:09Z",
                  "dimensions": [
                    {
                      "type": "ENERGY",
                      "volume": 1.973
                    }
                  ],
                  "tariff_id": "12"
                }
              ],
              "total_cost": {
                "excl_vat": 4.00,
                "incl_vat": 4.40
              },
              "total_energy": 15.342,
              "total_time": 1.973,
              "last_updated": "2015-06-29T22:01:13Z"
            }
            """.trimIndent()

        val result = service.validateCdr(payload)

        assertThat(result.valid).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `validateCpoTariff should reject payload with wrong energy product name`() {
        val payload =
            """
            {
              "country_code": "DE",
              "party_id": "ALL",
              "id": "17",
              "currency": "EUR",
              "elements": [
                {
                  "price_components": [
                    {
                      "type": "ENERGY",
                      "price": 0.50,
                      "vat": 20.0,
                      "step_size": 1
                    }
                  ]
                }
              ],
              "last_updated": "2018-12-17T11:36:01Z",
              "energy_mix": {
                "is_green_energy": true,
                "energy_product_name": "WRONG_PRODUCT"
              }
            }
            """.trimIndent()

        val result = service.validateCpoTariff(payload)

        assertThat(result.valid).isFalse()
        assertThat(result.errors).contains("energy_mix.energy_product_name must be BANULA_CPO_TARIFF")
    }

    @Test
    fun `validateSession should reject payload missing total cost`() {
        val payload =
            """
            {
              "country_code": "BE",
              "party_id": "BEC",
              "id": "101",
              "start_date_time": "2015-06-29T22:39:09Z",
              "kwh": 41.12,
              "cdr_token": {
                "country_code": "NL",
                "party_id": "TST",
                "uid": "123abc",
                "type": "RFID",
                "contract_id": "NL-TST-C12345678-S"
              },
              "auth_method": "WHITELIST",
              "location_id": "LOC1",
              "evse_uid": "3256",
              "connector_id": "1",
              "currency": "EUR",
              "status": "ACTIVE",
              "last_updated": "2015-06-29T23:50:17Z"
            }
            """.trimIndent()

        val result = service.validateSession(payload)

        assertThat(result.valid).isFalse()
        assertThat(result.errors).contains("Session must include total_cost")
    }

    @Test
    fun `validateLocation should reject payload missing tariff ids`() {
        val payload =
            """
            {
              "country_code": "BE",
              "party_id": "BEC",
              "id": "LOC1",
              "publish": true,
              "name": "Gent Zuid",
              "address": "F.Rooseveltlaan 3A",
              "city": "Gent",
              "postal_code": "9000",
              "country": "BEL",
              "coordinates": {
                "latitude": "51.047599",
                "longitude": "3.729944"
              },
              "parking_type": "ON_STREET",
              "evses": [
                {
                  "uid": "3256",
                  "evse_id": "BE*BEC*E041503001",
                  "status": "AVAILABLE",
                  "connectors": [
                    {
                      "id": "1",
                      "standard": "IEC_62196_T2",
                      "format": "CABLE",
                      "power_type": "AC_3_PHASE",
                      "max_voltage": 220,
                      "max_amperage": 16,
                      "max_electric_power": 150000,
                      "last_updated": "2015-03-16T10:10:02Z"
                    }
                  ],
                  "last_updated": "2015-06-28T08:12:01Z"
                }
              ],
              "energy_mix": {
                "is_green_energy": true,
                "energy_product_name": "BANULA"
              },
              "time_zone": "Europe/Brussels",
              "last_updated": "2015-06-29T20:39:09Z"
            }
            """.trimIndent()

        val result = service.validateLocation(payload)

        assertThat(result.valid).isFalse()
        assertThat(result.errors).contains("EVSE 1 connector 1 must include tariff_ids")
    }

    @Test
    fun `validateToken should reject payload with mismatched supplier`() {
        val payload =
            """
            {
              "country_code": "DE",
              "party_id": "TNM",
              "uid": "12345678905880",
              "type": "RFID",
              "contract_id": "DE-TNM-CACC12E4",
              "visual_number": "DF000-2001-8999-1",
              "issuer": "TheNewMotion",
              "valid": true,
              "whitelist": "NEVER",
              "default_profile_type": "GREEN",
              "energy_contract": {
                "supplier_name": "DE*WRONG",
                "contract_id": "DE-TNM-CACC12E4"
              },
              "last_updated": "2018-12-10T17:25:10Z"
            }
            """.trimIndent()

        val result = service.validateToken(payload)

        assertThat(result.valid).isFalse()
        assertThat(result.errors).contains("energy_contract.supplier_name must be DE*TNM")
    }

    @Test
    fun `validateCdr should reject payload without tariffs`() {
        val payload =
            """
            {
              "country_code": "DE",
              "party_id": "OLI",
              "id": "12345",
              "start_date_time": "2015-06-29T21:39:09Z",
              "end_date_time": "2015-06-29T23:37:32Z",
              "cdr_token": {
                "country_code": "DE",
                "party_id": "TNM",
                "uid": "012345678",
                "type": "RFID",
                "contract_id": "DE-8ACC12E46L89"
              },
              "authorization_reference": "REF45412",
              "auth_method": "WHITELIST",
              "cdr_location": {
                "id": "LOC1",
                "name": "OLI Office",
                "address": "Silberburgstr. 112",
                "city": "Stuttgart",
                "postal_code": "70176",
                "country": "DEU",
                "coordinates": {
                  "latitude": "3.729944",
                  "longitude": "51.047599"
                },
                "evse_uid": "3256",
                "evse_id": "DE*OLI*E041503003",
                "connector_id": "1",
                "connector_standard": "IEC_62196_T2",
                "connector_format": "SOCKET",
                "connector_power_type": "AC_1_PHASE"
              },
              "currency": "EUR",
              "tariffs": [],
              "charging_periods": [
                {
                  "start_date_time": "2015-06-29T21:39:09Z",
                  "dimensions": [
                    {
                      "type": "ENERGY",
                      "volume": 1.973
                    }
                  ],
                  "tariff_id": "12"
                }
              ],
              "total_cost": {
                "excl_vat": 4.00,
                "incl_vat": 4.40
              },
              "total_energy": 15.342,
              "total_time": 1.973,
              "last_updated": "2015-06-29T22:01:13Z"
            }
            """.trimIndent()

        val result = service.validateCdr(payload)

        assertThat(result.valid).isFalse()
        assertThat(result.errors).contains("CDR must include the applied tariff in the tariffs array")
    }
}
