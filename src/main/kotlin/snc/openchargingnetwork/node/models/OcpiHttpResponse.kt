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

package snc.openchargingnetwork.node.models

import io.ktor.http.*
import java.util.TreeMap
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import shareandcharge.openchargingnetwork.notary.SignableHeaders
import shareandcharge.openchargingnetwork.notary.ValuesToSign
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse

data class SyncedHttpResponse(
    val statusCode: HttpStatusCode,
    val headers: Headers,
    val contentType: ContentType?,
    val contentLength: Long?,
    val body: String,
    )

/**
 * HTTP header names are case-insensitive (RFC 9110), and HTTP/2 carries them lowercased on the
 * wire. Ktor's `Headers.toMap()` hands back a plain, case-sensitive Map that preserves whatever
 * casing the upstream happened to send, so a lookup of "Location" silently misses a header
 * delivered as "location". Wrapping the map here restores case-insensitive lookup for every
 * consumer of [OcpiHttpResponse.headers].
 */
fun Map<String, String>.toCaseInsensitiveHeaders(): Map<String, String> =
    TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).also { it.putAll(this) }

data class OcpiHttpResponse<T : Any>(
    val statusCode: Int,
    /** Case-insensitive when built via [toCaseInsensitiveHeaders] - see that function for why. */
    val headers: Map<String, String>,
    val body: OcpiResponse<T>? = null
) {
    fun toSignedValues(): ValuesToSign<OcpiResponse<T>> {
        return ValuesToSign(
            headers = SignableHeaders(
                limit = headers["X-Limit"] ?: headers["x-limit"],
                totalCount = headers["X-Total-Count"] ?: headers["x-total-count"],
                link = headers["Link"] ?: headers["link"],
                location = headers["Location"] ?: headers["location"]
            ),
            body = body
        )
    }
}

@Serializable
data class SpringErrorResponse(val timestamp: Instant, val status: Int, val error: String, val path: String? = null)

data class ControllerResponse<T>(val success: Boolean, val data: T? = null, val error: String? = null)