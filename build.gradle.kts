import java.nio.file.Files
import java.nio.file.Path

plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // HTML Parsing
    implementation("org.jsoup:jsoup:1.17.2")

    // JSON Serialization
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.google.code.gson:gson:2.10.1")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.10.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

application {
    mainClass.set("com.minigoogle.demo.MiniGoogleApp")
}

tasks.jar {
    archiveFileName.set("mini-google.jar")
    manifest {
        attributes["Main-Class"] = "com.minigoogle.demo.MiniGoogleApp"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Bundle runtime dependencies so `java -jar mini-google.jar` works standalone.
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

/**
 * Builds the React frontend (frontend/) with Vite and copies the single-file
 * output into the demo web resources. Requires Node.js; when Node is not
 * available the task is skipped so the checked-in resource still works.
 */
val frontendBuild by tasks.registering {
    group = "build"
    description = "Builds the React frontend and copies it into demo resources"
    inputs.dir(layout.projectDirectory.dir("frontend/src"))
    inputs.files(
        layout.projectDirectory.file("frontend/package.json"),
        layout.projectDirectory.file("frontend/vite.config.js"),
        layout.projectDirectory.file("frontend/index.html")
    )
    outputs.file(layout.projectDirectory.file("src/main/resources/demo/index.html"))
    doLast {
        val frontendDir = layout.projectDirectory.dir("frontend").asFile
        val node = listOf("node", "node.exe").firstOrNull { executableExists(it) }
        if (node == null) {
            logger.warn("Node.js not found - skipping frontend build (using checked-in resource)")
            return@doLast
        }
        val npm = if (System.getProperty("os.name").toLowerCase().contains("win")) "npm.cmd" else "npm"
        exec {
            workingDir = frontendDir
            commandLine(npm, "install", "--no-audit", "--no-fund")
        }
        exec {
            workingDir = frontendDir
            commandLine(npm, "run", "build")
        }
        val built = frontendDir.resolve("dist/index.html")
        if (!built.exists()) {
            throw GradleException("Frontend build did not produce dist/index.html")
        }
        built.copyTo(
            layout.projectDirectory.file("src/main/resources/demo/index.html").asFile,
            overwrite = true
        )
    }
}

fun executableExists(name: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    return path.split(System.getProperty("path.separator"))
        .map { Path.of(it, name) }
        .any { Files.exists(it) }
}

tasks.processResources {
    dependsOn(frontendBuild)
}

/**
 * Builds a verified SearchEngine index from a BEIR dataset. Reads corpus,
 * queries and qrels, persists the deterministic id mapping + manifest, and
 * prints the exact numbers that may go into the docs and resume.
 *
 * Usage:
 *   gradlew corpusIndex -Pbeir.dataset=trec-covid -Pbeir.dir=data/beir/trec-covid
 *                        [-Pbeir.out=build/beir-index] [-Pbeir.maxDocs=100000]
 *                        [-Pbeir.config=ranking.topK=100]
 */
val corpusIndex by tasks.registering(JavaExec::class) {
    group = "evaluation"
    description = "Loads a BEIR dataset and builds a verified search index"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.minigoogle.corpus.BeirIndexMain")
    jvmArgs = listOf("-Xmx12g", "-XX:MaxMetaspaceSize=512m")
    doFirst {
        providers.gradleProperty("beir.heap").orNull?.let {
            jvmArgs = listOf("-Xmx$it", "-XX:MaxMetaspaceSize=512m")
        }
        val dataset = providers.gradleProperty("beir.dataset").getOrElse("trec-covid")
        args = buildList {
            add("--dataset"); add(dataset)
            add("--dir"); add(providers.gradleProperty("beir.dir")
                .getOrElse("data/beir/$dataset"))
            add("--out"); add(providers.gradleProperty("beir.out")
                .getOrElse("build/beir-index/$dataset"))
            providers.gradleProperty("beir.maxDocs").orNull?.let {
                add("--maxDocs"); add(it)
            }
            providers.gradleProperty("beir.config").orNull?.let {
                add("--config"); add(it)
            }
        }
    }
}

/**
 * Runs BEIR retrieval evaluation over a built index and prints NDCG@10,
 * Recall@100, MRR@10 and MAP@100 per variant.
 *
 * Usage:
 *   gradlew corpusEval -Pbeir.dataset=trec-covid -Pbeir.dir=data/beir/trec-covid
 *                      [-Pbeir.out=build/beir-index] [-Pbeir.maxDocs=25000]
 *                      [-Pbeir.split=test] [-Pbeir.topK=100]
 *                      [-Pbeir.variants=hybrid,bm25]
 *                      [-Pbeir.config=semantic.hybrid.enabled=false]
 */
val corpusEval by tasks.registering(JavaExec::class) {
    group = "evaluation"
    description = "Runs BEIR retrieval evaluation (NDCG@10, Recall@100, MRR@10, MAP@100)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.minigoogle.corpus.BeirEvaluationMain")
    jvmArgs = listOf("-Xmx12g", "-XX:MaxMetaspaceSize=512m")
    doFirst {
        providers.gradleProperty("beir.heap").orNull?.let {
            jvmArgs = listOf("-Xmx$it", "-XX:MaxMetaspaceSize=512m")
        }
        val dataset = providers.gradleProperty("beir.dataset").getOrElse("trec-covid")
        args = buildList {
            add("--dataset"); add(dataset)
            add("--dir"); add(providers.gradleProperty("beir.dir")
                .getOrElse("data/beir/$dataset"))
            add("--out"); add(providers.gradleProperty("beir.out")
                .getOrElse("build/beir-index/$dataset"))
            providers.gradleProperty("beir.maxDocs").orNull?.let {
                add("--maxDocs"); add(it)
            }
            providers.gradleProperty("beir.split").orNull?.let {
                add("--split"); add(it)
            }
            providers.gradleProperty("beir.topK").orNull?.let {
                add("--topK"); add(it)
            }
            providers.gradleProperty("beir.variants").orNull?.let {
                add("--variants"); add(it)
            }
            providers.gradleProperty("beir.config").orNull?.let {
                add("--config"); add(it)
            }
        }
    }
}
