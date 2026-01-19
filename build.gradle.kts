plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.noarg") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "3.5.9"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "lv.askolds"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

noArg {
    annotation("ai.timefold.solver.core.api.domain.solution.PlanningSolution")
    annotation("ai.timefold.solver.core.api.domain.entity.PlanningEntity")
    invokeInitializers = true
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Timefold with Spring Boot integration
    implementation(platform("ai.timefold.solver:timefold-solver-bom:1.30.0"))
    implementation("ai.timefold.solver:timefold-solver-spring-boot-starter")
    implementation("ai.timefold.solver:timefold-solver-jackson")
    implementation("ai.timefold.solver:timefold-solver-benchmark")

    // Jackson Kotlin module
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // GraphHopper for routing
    implementation("com.graphhopper:graphhopper-core:11.0")

    // OpenAPI/Swagger documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.15")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("ai.timefold.solver:timefold-solver-test")
    testImplementation(kotlin("test"))
}

// Configure Spring Boot main class
springBoot {
    mainClass.set("app.StreetRoutingApplicationKt")
}


kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()

    // Exclude long-running benchmark tests by default
    if (!project.hasProperty("benchmark.full")) {
        systemProperty("benchmark.full", "false")
    } else {
        systemProperty("benchmark.full", project.property("benchmark.full").toString())
    }
}

// Task to run quick benchmarks
tasks.register<Test>("benchmark") {
    group = "verification"
    description = "Run quick benchmark tests"
    useJUnitPlatform {
        includeTags("benchmark")
    }
    filter {
        includeTestsMatching("benchmark.*")
    }
    systemProperty("benchmark.full", "false")
}

// Task to run full benchmarks (longer duration)
tasks.register<Test>("benchmarkFull") {
    group = "verification"
    description = "Run full benchmark tests (longer duration)"
    useJUnitPlatform()
    filter {
        includeTestsMatching("benchmark.*")
    }
    systemProperty("benchmark.full", "true")
}

// Task to generate benchmark data files
tasks.register<JavaExec>("generateBenchmarkData") {
    group = "benchmark"
    description = "Generate benchmark data files"
    mainClass.set("benchmark.GenerateBenchmarkDataKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Task to run Timefold benchmark with HTML report
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "Run Timefold benchmark with HTML report. Args: quick (default), full, scalability, generate"
    mainClass.set("benchmark.RunBenchmarkKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Pass any command line arguments
    if (project.hasProperty("args")) {
        args = (project.property("args") as String).split(" ")
    }
}