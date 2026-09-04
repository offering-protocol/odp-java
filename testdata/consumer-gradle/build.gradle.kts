plugins {
    java
}

val odpVersion = providers.gradleProperty("odpVersion").getOrElse("0.2.1")

repositories {
    providers.gradleProperty("odpRepository").orNull?.let {
        maven {
            url = uri(it)
        }
    }
    mavenCentral()
}

dependencies {
    implementation(platform("org.offeringprotocol:odp-bom:$odpVersion"))
    implementation("org.offeringprotocol:odp-json-jackson2")
    implementation("org.offeringprotocol:odp-core")
    implementation("org.offeringprotocol:odp-directory")
    implementation("org.offeringprotocol:odp-agent")
    implementation("org.offeringprotocol:odp-service")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

sourceSets {
    main {
        java {
            srcDir("../consumer/src/main/java")
        }
    }
}

tasks.register<JavaExec>("verifyConsumer") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.offeringprotocol.example.Consumer"
}
