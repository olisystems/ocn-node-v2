import org.gradle.kotlin.dsl.withType

plugins {
	kotlin("jvm") version "2.1.20"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.4.5"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.asciidoctor.jvm.convert") version "3.3.2"
	kotlin("plugin.jpa") version "1.9.25"
	// configuration processing
	kotlin("kapt") version "1.3.72"
	// json serialization
	kotlin("plugin.serialization") version "2.1.20"
}

group = "snc.openchargingnetwork"
version = "ocn-v3"


extra["snippetsDir"] = file("build/generated-snippets")

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}


repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
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
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
	implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
	implementation("org.web3j:core:5.0.0")
	implementation("com.jayway.jsonpath:json-path:2.9.0")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	kapt("org.springframework.boot:spring-boot-configuration-processor")
	implementation("io.ktor:ktor-client-core:3.1.3")
	implementation("io.ktor:ktor-client-cio:3.1.3")
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

tasks.register("bootRunDev") {
	group = "application"
	description = "Runs this project as a Spring Boot application with the dev profile"
	doFirst {
		tasks.bootRun.configure {
			systemProperty("spring.profiles.active", "dev")
		}
	}
	finalizedBy("bootRun")
}

tasks.register("bootRunTest") {
	group = "application"
	description = "Runs this project as a Spring Boot application with the dev profile"
	doFirst {
		tasks.bootRun.configure {
			systemProperty("spring.profiles.active", "test")
		}
	}
	finalizedBy("bootRun")
}

tasks.register("bootRunLocalMiniKube") {
	group = "application"
	description = "Runs this project as a Spring Boot application with the dev profile"
	doFirst {
		tasks.bootRun.configure {
			systemProperty("spring.profiles.active", "localMinikube")
		}
	}
	finalizedBy("bootRun")
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

