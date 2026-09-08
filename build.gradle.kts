plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.blocke"
version = "1.1.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
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

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("Bloeco")
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("Bloeco")
    archiveClassifier.set("")
}
