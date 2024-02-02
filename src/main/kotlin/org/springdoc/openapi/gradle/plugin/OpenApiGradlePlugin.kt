package org.springdoc.openapi.gradle.plugin

import com.github.psxpaul.task.JavaExecFork
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.jvm.Jvm
import org.springframework.boot.gradle.tasks.run.BootRun

open class OpenApiGradlePlugin : Plugin<Project> {

	override fun apply(project: Project) {
		// Runtime dependencies on the following plugins
		project.plugins.apply("org.springframework.boot")
		project.plugins.apply("com.github.psxpaul.execfork")

		project.extensions.create("openApi", OpenApiExtension::class.java)
		project.tasks.register("forkedSpringBootRun", JavaExecFork::class.java)
		project.tasks.register("generateOpenApiDocs", OpenApiGeneratorTask::class.java)

		generate(project)
	}

	private fun generate(project: Project) = project.run {
		val tasksNames = tasks.names
		if (!tasksNames.contains("bootRunMainClassName") && tasksNames.contains("resolveMainClassName")) {
			tasks.register("bootRunMainClassName") { it.dependsOn(tasks.named("resolveMainClassName")) }
		}

		// The task, used to run the Spring Boot application (`bootRun`)
		val bootRunTask = tasks.named("bootRun")
		// The task, used to resolve the application's main class (`bootRunMainClassName`)
		val forkedDependency  = if (tasksNames.contains("bootRunMainClassName")) {
			"bootRunMainClassName"
		} else {
			"bootRun"
		}

		val extension = extensions.findByName(EXTENSION_NAME) as OpenApiExtension
		val customBootRun = extension.customBootRun
		// Create a forked version spring boot run task
		val forkedSpringBoot = tasks.named(
			FORKED_SPRING_BOOT_RUN_TASK_NAME,
			JavaExecFork::class.java
		) { fork ->
			fork.dependsOn(tasks.named(forkedDependency))
			fork.onlyIf { needToFork(bootRunTask, customBootRun, fork) }
		}

		// This is my task. Before I can run it, I have to run the dependent tasks
		val openApiTask =
			tasks.named(OPEN_API_TASK_NAME, OpenApiGeneratorTask::class.java) {
				it.dependsOn(forkedSpringBoot)
			}

		// The forked task need to be terminated as soon as my task is finished
		forkedSpringBoot.get().stopAfter = openApiTask as TaskProvider<Task>
	}

	private fun needToFork(
		bootRunTask: TaskProvider<Task>,
		customBootRun: CustomBootRunAction,
		fork: JavaExecFork
	): Boolean {
		val bootRun = bootRunTask.get() as BootRun

		val baseSystemProperties =
			customBootRun.systemProperties.orNull?.takeIf { it.isNotEmpty() }
				?: bootRun.systemProperties
		with(fork) {
			// copy all system properties, excluding those starting with `java.class.path`
			systemProperties = baseSystemProperties.filter {
				!it.key.startsWith(CLASS_PATH_PROPERTY_NAME)
			}

			// use original bootRun parameter if the list-type customBootRun properties are empty
			workingDir = customBootRun.workingDir.asFile.orNull
				?: fork.workingDir
			args = customBootRun.args.orNull?.takeIf { it.isNotEmpty() }?.toMutableList()
				?: bootRun.args?.toMutableList() ?: mutableListOf()
			classpath = customBootRun.classpath.takeIf { !it.isEmpty }
				?: bootRun.classpath
			main = customBootRun.mainClass.orNull
				?: bootRun.mainClass.get()
			jvmArgs = customBootRun.jvmArgs.orNull?.takeIf { it.isNotEmpty() }
				?: bootRun.jvmArgs
			environment = customBootRun.environment.orNull?.takeIf { it.isNotEmpty() }
				?: bootRun.environment
			if (Jvm.current().toString().startsWith("1.8")) {
				killDescendants = false
			}
		}
		return true
	}
}
