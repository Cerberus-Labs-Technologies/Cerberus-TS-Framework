plugins { kotlin("jvm") }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies { compileOnly(project(":")) }
