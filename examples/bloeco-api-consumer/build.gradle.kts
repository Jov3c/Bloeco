plugins { java }

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly(files("../../build/libs/Bloeco-1.5.0-SNAPSHOT.jar"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
