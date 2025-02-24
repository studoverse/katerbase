import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `maven-publish`
  kotlin("jvm") version "2.1.0"
}

group = "com.moshbit.katerbase"
version = "0.1.3"

repositories {
  mavenCentral()
}

dependencies {
  val sentry_version = "7.20.1"

  implementation(kotlin("stdlib"))

  implementation("org.mongodb:mongodb-driver-sync:4.9.0")

  implementation("io.sentry:sentry:$sentry_version")
  implementation("io.sentry:sentry-kotlin-extensions:$sentry_version")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

  implementation("com.fasterxml.jackson.core:jackson-core:2.14.2")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")

  implementation("ch.qos.logback:logback-classic:1.5.16")

  implementation("commons-codec:commons-codec:1.15")

  testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "21"
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
}

kotlin {
  jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of("21")) // Auto-download JDK for developers
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