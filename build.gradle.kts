import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.0.6"
}

group = "com.ktx"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    // Spring Boot BOM. platform()은 추가한 configuration과 그 하위에만 전파되므로,
    // implementation을 확장하지 않는 compileOnly/annotationProcessor 계열에도 직접 적용한다.
    val springBootBom = platform(SpringBootPlugin.BOM_COORDINATES)
    implementation(springBootBom)
    compileOnly(springBootBom)
    annotationProcessor(springBootBom)
    testCompileOnly(springBootBom)
    testAnnotationProcessor(springBootBom)

    // null 계약 명시 (Spring Framework 7 채택). 버전은 Spring Boot BOM이 관리.
    implementation("org.jspecify:jspecify")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson-spring-boot-starter:4.4.0")
    runtimeOnly("com.mysql:mysql-connector-j")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    mockitoAgent("org.mockito:mockito-core:5.14.2") { isTransitive = false }

    // 통합 테스트 인프라(MySQL/Redis)를 Testcontainers로 자동 기동. 버전은 Spring Boot BOM이 관리(2.0.x).
    // 2.0.x부터 모듈 아티팩트명에 testcontainers- 접두사가 붙고, redis 전용 모듈은 없으므로
    // Redis는 testcontainers 코어의 GenericContainer로 띄운다.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs.add("-javaagent:${mockitoAgent.asPath}")
}

