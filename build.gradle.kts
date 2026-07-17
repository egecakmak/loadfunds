plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.hibernate.orm") version "7.4.1.Final"
	id("org.graalvm.buildtools.native") version "1.1.1"
}

group = "ca.venn"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-h2console")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.hibernate.orm:hibernate-micrometer")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("com.h2database:h2")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

val integrationTest by sourceSets.creating {
	java.setSrcDirs(listOf("src/integrationTest/java"))
	resources.setSrcDirs(listOf("src/integrationTest/resources"))
	compileClasspath += sourceSets.main.get().output
	runtimeClasspath += sourceSets.main.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
	description = "Runs the API integration test suite against a running loadfunds service"
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	testClassesDirs = integrationTest.output.classesDirs
	classpath = integrationTest.runtimeClasspath
	shouldRunAfter(tasks.test)
	useJUnitPlatform()
	outputs.upToDateWhen { false }

	val baseUrl = providers.gradleProperty("loadfunds.base-url")
		.orElse(providers.environmentVariable("LOADFUNDS_BASE_URL"))
		.orElse("http://localhost:8080")
	doFirst {
		systemProperty("loadfunds.base-url", baseUrl.get())
	}
}

tasks.register<JavaExec>("replayLoadFunds") {
	description = "Replays a JSONL load-funds input file against a running service"
	group = "application"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("ca.venn.loadfunds.replay.LoadFundsReplay")
	outputs.upToDateWhen { false }

	doFirst {
		val replayArgs = mutableListOf(
			"--base-url=" + providers.gradleProperty("loadfunds.base-url")
				.orElse(providers.environmentVariable("LOADFUNDS_BASE_URL"))
				.orElse("http://localhost:8080").get(),
			"--input=" + providers.gradleProperty("loadfunds.input")
				.orElse("src/integrationTest/resources/Venn - Back-End - Input.txt").get(),
			"--output=" + providers.gradleProperty("loadfunds.output")
				.orElse("src/integrationTest/resources/Venn - Back-End - Replay Generated Output .txt").get(),
			"--detailed-output=" + providers.gradleProperty("loadfunds.detailedOutput")
				.orElse("build/replay/responses.jsonl").get()
		)
		providers.gradleProperty("loadfunds.ground-truth").orNull?.let {
			replayArgs.add("--ground-truth=$it")
		}
		setArgs(replayArgs)
	}
}

tasks.register<JavaExec>("runFile") {
	description = "Processes a load-funds input file without starting the HTTP server"
	group = "application"
	dependsOn(tasks.classes)
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("ca.venn.loadfunds.LoadfundsApplication")
	outputs.upToDateWhen { false }

	doFirst {
		val input = providers.gradleProperty("input").orNull
			?: throw GradleException("Missing input path: use -Pinput=<path>")
		val runnerArgs = mutableListOf(
			"--velocity.input=$input",
			"--spring.main.web-application-type=none",
			"--spring.main.banner-mode=off",
			"--logging.level.root=DEBUG",
			"--logging.level.WIRE=DEBUG"
		)
		providers.gradleProperty("output").orNull
			?.takeIf { it.isNotBlank() }
			?.let { runnerArgs.add("--velocity.output=$it") }
		setArgs(runnerArgs)
	}
}

hibernate {
	enhancement {
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty(
		"logging.file.name",
		layout.buildDirectory.file("test-logs/$name.log").get().asFile.absolutePath
	)
}
