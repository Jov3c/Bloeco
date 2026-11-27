plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.blocke"
version = "1.6.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("io.lettuce:lettuce-core:6.5.0.RELEASE")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0") {
        // Gradle 8.14's bundled test launcher supports JUnit Platform 1.x, while
        // this MockBukkit release transitively requests the incompatible JUnit 6 platform.
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

val apiJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages the public Bloeco API for consumer plugins to compile against."
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) {
        include("com/blocke/bloeco/api/**")
    }
}

tasks.assemble { dependsOn(apiJar) }

tasks.test {
    dependsOn(apiJar)
    systemProperty("bloeco.api.jar", apiJar.get().archiveFile.get().asFile.absolutePath)
    useJUnitPlatform { excludeTags("mysql-integration") }
    dependsOn(tasks.shadowJar)
    systemProperty("bloeco.shadow.jar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<Test>("mysqlTest") {
    description = "Runs MySQL integration and concurrency tests against an isolated database."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("mysql-integration") }
    environment("BLOECO_MYSQL_STRESS", "true")
    outputs.upToDateWhen { false }
    doFirst {
        val required = listOf("BLOECO_MYSQL_TEST_URL", "BLOECO_MYSQL_TEST_USER", "BLOECO_MYSQL_TEST_PASSWORD")
        val missing = required.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("Configure an isolated MySQL test database. Missing: ${missing.joinToString()}")
        }
    }
}

tasks.jar {
    archiveBaseName.set("Bloeco")
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("Bloeco")
    archiveClassifier.set("")
    mergeServiceFiles()
}
