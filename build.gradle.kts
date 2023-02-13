import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `maven-publish`
  kotlin("jvm") version "1.8.10"
}

group = "com.moshbit.katerbase"
version = "0.1.3"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))

  implementation("org.mongodb:mongodb-driver-sync:4.9.0")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

  implementation("com.fasterxml.jackson.core:jackson-core:2.14.2")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")

  implementation("ch.qos.logback:logback-classic:1.2.11")

  implementation("commons-codec:commons-codec:1.15")

  testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "11"
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
}

kotlin {
  jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of("17")) // Auto-download JDK for developers
  }
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