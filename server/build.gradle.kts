import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.sqlite)
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.url.connection)
    implementation(libs.jedis)
    implementation(libs.nimbus.jose.jwt)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.embedded.redis)
    testRuntimeOnly(libs.embedded.postgres.binaries.darwin.arm64v8)
}

application {
    mainClass.set("com.glosso.server.ApplicationKt")
}

// Compiles the authored content into publishable banks: fills IPA (CMUdict for
// English, the published lexicon for other languages) and validates every item.
// Exits non-zero if any item is invalid.
//   ./gradlew :server:publishContent -Planguage=<code> [-Plevels=A1,A2]
tasks.register<JavaExec>("publishContent") {
    group = "content"
    description = "Validate the content banks and fill reference IPA for one language"
    mainClass.set("com.glosso.server.tools.ContentPublisherKt")
    classpath = sourceSets["main"].runtimeClasspath
    dependsOn(tasks.named("classes"))

    val language = providers.gradleProperty("language").getOrElse("en")
    val dataRoot = rootProject.file("../glosso-data/content/$language")
    val out = providers.gradleProperty("out").getOrElse("${dataRoot.canonicalPath}/dist")
    args(
        "--content", providers.gradleProperty("content").getOrElse(dataRoot.canonicalPath),
        "--language", language,
        "--out", out,
    )
    providers.gradleProperty("levels").orNull?.let { args("--levels", it) }
    val cmudict = providers.gradleProperty("cmudict").orNull
    if (cmudict != null) {
        args("--cmudict", cmudict)
    } else if (language == "en") {
        args("--cmudict", rootProject.file("data/assets/cmudict.dict").canonicalPath)
    } else {
        val lexicon = File(dataRoot, "$language.lexicon")
        if (lexicon.isFile) args("--lexicon", lexicon.canonicalPath)
    }
}
