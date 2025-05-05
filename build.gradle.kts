import org.gradle.kotlin.dsl.withType

plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.4.5"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.asciidoctor.jvm.convert") version "3.3.2"
	kotlin("plugin.jpa") version "1.9.25"
	// asd
	kotlin("kapt") version "1.3.72"
}

group = "snc.openchargingnetwork"
version = "1.3.0"

extra["snippetsDir"] = file("build/generated-snippets")

configurations {
	runtimeClasspath {
		extendsFrom(implementation.get())
	}
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

val test: Test by tasks
test.apply {
	description = "Runs OCN Node unit and integration tests (note: integration tests depend on ganache-cli running)."
	dependsOn("unitTest", "integrationTest")
	outputs.dir(project.extra["snippetsDir"]!!)
}

tasks.register<Test>("unitTest") {
	group = "verification"
	description = "Runs OCN Node unit tests."
	useJUnitPlatform()
	exclude("**/integration/**")
}

tasks.register<Test>("integrationTest") {
	group = "verification"
	description = "Runs OCN Node integration tests (note: depends on a ganache-cli process running)."
	useJUnitPlatform()
	include("**/integration/**")
}

(tasks.getByName("processResources") as ProcessResources).apply {
	val profile: String by project
	println("Building using **/application.$profile.properties")
	include("**/application.$profile.properties")
	rename { "application.properties" }
}

tasks.register<Tar>("archive") {
	group = "build"
	description = "Assembles a tar archive with GZIP compression for distribution."
	archiveFileName.set("ocn-node-${project.version}.tar.gz")
	destinationDirectory.set(file("build/dist"))
	compression = Compression.GZIP

	into ("/ocn-node-${project.version}") {
		from(
			"build/libs/ocn-node-${project.version}.jar",
			"src/main/resources/application.dev.properties",
			"src/main/resources/application.prod.properties",
			"infra/ocn-node.service",
			"README.md",
			"CONFIGURATION.md",
			"CHANGELOG.md"
		)
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test") {
		exclude(module = "android-json")
	}
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Project-Specific
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.danilopianini:khttp:1.6.3")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
	implementation("org.web3j:core:5.0.0")
	implementation("com.jayway.jsonpath:json-path:2.9.0")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	kapt("org.springframework.boot:spring-boot-configuration-processor")
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.test {
	outputs.dir(project.extra["snippetsDir"]!!)
}

tasks.asciidoctor {
	inputs.dir(project.extra["snippetsDir"]!!)
	dependsOn(tasks.test)
}
