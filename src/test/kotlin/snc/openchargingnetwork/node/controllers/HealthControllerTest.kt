package snc.openchargingnetwork.node.controllers

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.core.env.Environment
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@WebMvcTest(HealthController::class)
@ExtendWith(RestDocumentationExtension::class)
class HealthControllerTest {

    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var env: Environment
    lateinit var apiPrefix: String

    @BeforeEach
    fun setUp(webApplicationContext: WebApplicationContext,
                   restDocumentation: RestDocumentationContextProvider) {

        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
                .build()

        apiPrefix = env.getProperty("ocn.node.apiPrefix") ?: ""
    }

    @Test
    fun `When GET health should return OK`() {
        mockMvc.perform(get("/$apiPrefix/health"))
                .andExpect(status().isOk)
                .andExpect(content().string("OK"))
                .andDo(document("health"))
    }
}