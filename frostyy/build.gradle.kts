plugins {
    java
}

group = "dev.reforgedfrost"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    processResources {
        // only filter plugin.yml, never the model
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    jar {
        archiveFileName.set("ReforgedFrost.jar")
    }
}
