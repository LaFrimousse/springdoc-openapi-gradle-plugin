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

		val extension = project.extensions.create("openApi", OpenApiExtension::class.java)
		//extension.message.convention("Hello from GreetingPlugin")

		val forkProvider = project.tasks.register("forkedSpringBootRun", JavaExecFork::class.java){fork ->
			fork.dependsOn(
				project.tasks.named("resolveMainClassName"),
				//project.tasks.named("compileJava")
			)

			fork.onlyIf { needToFork(project.tasks.named("bootRun"), extension.customBootRun, fork)}
		}

		project.tasks.register("generateOpenApiDocs", OpenApiGeneratorTask::class.java){
			it.dependsOn(
				project.tasks.named("forkedSpringBootRun")
			)
		}

		//todo: configuration avoidance here ??
		forkProvider.get().stopAfter = project.tasks.named(OPEN_API_TASK_NAME)

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
