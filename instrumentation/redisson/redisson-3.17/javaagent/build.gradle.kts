plugins {
  id("otel.javaagent-instrumentation")
}

muzzle {
  pass {
    group.set("org.redisson")
    module.set("redisson")
    versions.set("[3.17.0,)")
    assertInverse.set(true)
  }
}

dependencies {
  library("org.redisson:redisson:3.17.0")

  implementation(project(":instrumentation:redisson:redisson-common:javaagent"))

  compileOnly("com.google.auto.value:auto-value-annotations")
  annotationProcessor("com.google.auto.value:auto-value")

  testInstrumentation(project(":instrumentation:redisson:redisson-3.0:javaagent"))

  testImplementation(project(":instrumentation:redisson:redisson-common:testing"))
}

tasks.test {
  systemProperty("testLatestDeps", findProperty("testLatestDeps") as Boolean)
  usesService(gradle.sharedServices.registrations["testcontainersBuildService"].getService())
}
