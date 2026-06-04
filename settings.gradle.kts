plugins {
    // 로컬에 JDK 25가 없어도 toolchain JDK를 자동 프로비저닝
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ktx-ticketing-be"
