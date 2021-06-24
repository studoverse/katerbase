import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `maven-publish`
  kotlin("jvm") version "1.5.20"
}

group = "com.moshbit.katerbase"
version = "0.1.2"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))

  implementation("org.mongodb:mongodb-driver-sync:4.1.0")

  implementation("com.fasterxml.jackson.core:jackson-core:2.11.2")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.11.2")

  implementation("ch.qos.logback:logback-classic:1.2.3")

  implementation("commons-codec:commons-codec:1.14")

  testImplementation("org.junit.jupiter:junit-jupiter:5.6.0")
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
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