plugins {
  id("com.gradle.develocity") version "4.0.2"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.2.1"
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
apply(from = "../../gradle/develocity.gradle")

rootProject.name = "indexable"
