import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.spotbugs") version "6.2.4"
    id("com.diffplug.spotless") version "6.25.0"
    pmd
}

group = "iwkms.roomflow"
version = "0.0.1-SNAPSHOT"
description = "RoomFlow"
extra["testcontainers.version"] = "2.0.2"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13")
    implementation("org.apache.commons:commons-lang3:3.19.0")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")
    implementation("software.amazon.awssdk:s3:2.33.12")
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("com.github.dockerjava.api.version", "1.44")
}

tasks.test {
    description = "Runs isolated service unit tests without Docker or Spring startup."
    exclude("**/*IT.class", "**/RoomFlowApplicationTests.class")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs API, PostgreSQL and S3 integration tests. Requires Docker."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class", "**/RoomFlowApplicationTests.class")
    shouldRunAfter(tasks.test)
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test, integrationTest)
    executionData.setFrom(layout.buildDirectory.file("jacoco/test.exec"), layout.buildDirectory.file("jacoco/integrationTest.exec"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    executionData.setFrom(layout.buildDirectory.file("jacoco/test.exec"), layout.buildDirectory.file("jacoco/integrationTest.exec"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf(
                "*.BookingManagementService",
                "*.RefreshTokenService",
                "*.FileStorageService",
                "*.AuthService",
                "*.AdminUserService",
            )
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

spotbugs {
    effort = Effort.MAX
    reportLevel = Confidence.MEDIUM
    excludeFilter = file("config/spotbugs/spotbugsExclude.xml")
}

tasks.withType<SpotBugsTask> {
    reports.create("html") {
        required = true
        outputLocation = layout.buildDirectory.dir("reports/spotbugs/main/spotbugs.html").get().asFile
    }
    reports.create("xml") {
        required = false
    }
}

spotless {
    format("misc") {
        target("*.gradle", "*.md", ".gitignore", "*.yml", "*.properties")
        trimTrailingWhitespace()
        indentWithSpaces(4)
        endWithNewline()
    }
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "settings.gradle.kts")
        ktlint("0.50.0")
        indentWithSpaces(4)
        trimTrailingWhitespace()
        endWithNewline()
    }
}

pmd {
    toolVersion = "7.21.0"
    isConsoleOutput = true
    ruleSets = listOf("category/java/bestpractices.xml")
}

tasks.named("check") {
    dependsOn("spotbugsMain", "spotbugsTest", "spotlessCheck", "pmdMain", integrationTest, tasks.jacocoTestCoverageVerification)
}

tasks.named("pmdTest") {
    enabled = false
}

tasks.processResources {
    from("frontend/dist") {
        into("static")
    }
}
