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

data class OcpiHttpResponse<T : Any>(
    val statusCode: Int,
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

data class ControllerResponse(val success: Boolean, val data: GqlData? = null, val error: String? = null)