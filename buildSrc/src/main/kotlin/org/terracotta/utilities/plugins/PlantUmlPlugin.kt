/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.utilities.plugins

import net.sourceforge.plantuml.Option
import net.sourceforge.plantuml.SourceFileReader
import net.sourceforge.plantuml.file.FileGroup
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.tasks.JvmConstants
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.execution.MultipleBuildFailures
import org.gradle.external.javadoc.JavadocOptionFileOption
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.external.javadoc.internal.JavadocOptionFileWriterContext
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * Uses PlantUML to generate diagrams described in comments blocks in Java source files.
 *
 * @author Clifford W. Johnson
 * @see <a href="https://plantuml.com/javadoc">Generate diagrams with Javadoc / Legacy Javadoc</a>
 */
class PlantUmlPlugin : Plugin<Project> {
  override fun apply(project: Project) {

    project.plugins.withType<JvmEcosystemPlugin>().configureEach {
      project.extensions.configure<SourceSetContainer> {
        configureEach {
          val sourceSet = this
          val sourceSetName = this.name
          val javaSourceSet = this.java

          // Register a PlantUML task for this sourceSet
          val plantUml = project.tasks.register(this.getTaskName("generate", "plantuml"), PlantUmlTask::class.java) {
            this.group = JvmConstants.DOCUMENTATION_GROUP
            this.description = "Generates PlantUML diagrams within ${sourceSetName} Java source files"
            this.outputDirectory.convention(project.layout.buildDirectory.dir("generated/plantuml/${sourceSetName}"))
            this.charset.convention(StandardCharsets.UTF_8.name())
            this.verbose.convention(project.logging.level == LogLevel.DEBUG)
            this.plantUmlExcludes.convention(emptyList())
            this.source = javaSourceSet
          }

          // Connect the Javadoc task associated with this SourceSet to the PlantUML task just registered
          project.tasks.withType(Javadoc::class.java)
            .configureEach {
              if (sourceSet.javadocTaskName.equals(this.name)) {
                dependsOn(plantUml)
                options {
                  (this as StandardJavadocDocletOptions).docFilesSubDirs(true)
                  this.addOption(
                    project.objects.newInstance(LazyFileJavadocOptionFileOption::class.java,
                      "sourcepath", plantUml.flatMap { it.outputDirectory })
                  )
                }
              }
            }
        }
      }
    }
  }
}

/**
 * Provides a lazily-configurable Javadoc option for a file value.
 */
// Implementing methods conflicting with auto-generated getter/setter -- https://stackoverflow.com/a/50871196/1814086
open class LazyFileJavadocOptionFileOption @Inject constructor(
  private val option: String,
  private var value: Any,
  val project: Project
) :
  JavadocOptionFileOption<Any> {

  override fun getOption(): String = option

  override fun getValue(): Any = value

  override fun setValue(value: Any) {
    this.value = value
  }

  override fun write(writerContext: JavadocOptionFileWriterContext) {
    writerContext.writeValueOption(option, project.file(value).absolutePath)
  }
}

/**
 * The Gradle task invoking <code>plantuml</code> over the configured source files.
 */
abstract class PlantUmlTask : SourceTask() {

  /**
   * PlantUML output directory corresponding to the '-output' option.
   * Default is "${buildDirectory}/generated/plantuml/${soruceSet.name}".
   */
  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  /**
   * Charset used for PlantUML output corresponding to the '-charset' option.
   * Defaults to "UTF-8".
   */
  @get:Input
  abstract val charset: Property<String>

  /**
   * Generate diagnostic output corresponding to the '-verbose' option.
   * Default to true if Gradle logging level is [LogLevel.DEBUG]; false otherwise.
   */
  @get:Internal
  abstract val verbose: Property<Boolean>

  /**
   * Collection of file exclusion patterns corresponding to the PlantUML '-exclude' option.
   * Defaults to an empty list.
   */
  @get:Input
  abstract val plantUmlExcludes: ListProperty<String>

  @TaskAction
  fun generate() {
    val outputDir = outputDirectory.get().asFile
    outputDir.mkdirs()

    val faults = mutableListOf<GradleException>()
    source.visit {
      if (!this.isDirectory) {
        val oDir = File(outputDir, this.relativePath.parent.pathString)

        val fileGroup = FileGroup(file.absolutePath, plantUmlExcludes.get(), Option())
        fileGroup.files.forEach { file ->
          val reader = SourceFileReader(file, oDir, charset.get())
          val images = reader.generatedImages
          images.forEach {
            if (it.lineErrorRaw() == -1) {
              logger.info("PlantUML generated ${it.pngFile} from $it")
            } else {
              val message = "PlantUML generation failed for $it at line ${it.lineErrorRaw()}"
              faults.add(GradleException(message))
            }
          }
        }
      }
    }
    if (faults.isNotEmpty()) {
      throw MultipleBuildFailures(faults)
    }
  }
}