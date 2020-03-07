import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `maven-publish`
  kotlin("jvm") version "1.3.41"
}

group = "com.moshbit.katerbase"
version = "0.1.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))

  compile("org.mongodb:mongo-java-driver:3.9.1")

  compile("com.fasterxml.jackson.core:jackson-core:2.9.8")
  compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.8")

  compile("ch.qos.logback:logback-classic:1.2.3")

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