plugins {
  `maven-publish`
  kotlin("jvm") version "1.9.0"
}

group = "com.moshbit.katerbase"
version = "0.1.3"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))

  implementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.10.1")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

  implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

  implementation("ch.qos.logback:logback-classic:1.2.11")

  implementation("commons-codec:commons-codec:1.15")

  testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
}

kotlin {
  jvmToolchain(17) // Auto-download JDK for developers
}

publishing {
  publications {
    create<MavenPublication>("default") {
      from(components["java"])
    }
  }
  repositories {
    maven {
      url = uri("$buildDir/repository")
    }
  }
}