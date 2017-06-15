package org.jetbrains.intellij

import com.intellij.structure.domain.IdeVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.internal.artifacts.publish.ArchivePublishArtifact
import org.gradle.api.internal.plugins.DefaultArtifactPublicationSet
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.jvm.Jvm
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.tooling.BuildException
import org.jetbrains.annotations.NotNull
import org.jetbrains.intellij.dependency.IdeaDependencyManager
import org.jetbrains.intellij.dependency.PluginDependencyManager
import org.jetbrains.intellij.dependency.PluginProjectDependency
import org.jetbrains.intellij.tasks.*

class IntelliJPlugin implements Plugin<Project> {
    public static final GROUP_NAME = "intellij"
    public static final EXTENSION_NAME = "intellij"
    public static final String DEFAULT_SANDBOX = 'idea-sandbox'
    public static final String PATCH_PLUGIN_XML_TASK_NAME = "patchPluginXml"
    public static final String PLUGIN_XML_DIR_NAME = "patchedPluginXmlFiles"
    public static final String PREPARE_SANDBOX_TASK_NAME = "prepareSandbox"
    public static final String PREPARE_TESTING_SANDBOX_TASK_NAME = "prepareTestingSandbox"
    public static final String RUN_IDEA_TASK_NAME = "runIdea"
    public static final String RUN_IDE_TASK_NAME = "runIde"
    public static final String BUILD_PLUGIN_TASK_NAME = "buildPlugin"
    public static final String PUBLISH_PLUGIN_TASK_NAME = "publishPlugin"

    public static final String IDEA_CONFIGURATION_NAME = "idea"
    public static final String IDEA_PLUGINS_CONFIGURATION_NAME = "ideaPlugins"

    public static final LOG = Logging.getLogger(IntelliJPlugin)
    public static final String DEFAULT_IDEA_VERSION = "LATEST-EAP-SNAPSHOT"
    public static final String DEFAULT_INTELLIJ_REPO = 'https://www.jetbrains.com/intellij-repository'

    @Override
    void apply(Project project) {
        def javaPlugin = project.getPlugins().apply(JavaPlugin)
        def intellijExtension = project.extensions.create(EXTENSION_NAME, IntelliJPluginExtension) as IntelliJPluginExtension
        intellijExtension.with {
            plugins = []
            version = DEFAULT_IDEA_VERSION
            type = 'IC'
            pluginName = project.name
            sandboxDirectory = new File(project.buildDir, DEFAULT_SANDBOX).absolutePath
            instrumentCode = true
            updateSinceUntilBuild = true
            sameSinceUntilBuild = false
            intellijRepo = DEFAULT_INTELLIJ_REPO
            downloadSources = !System.getenv().containsKey('CI')
            publish = new IntelliJPluginExtension.Publish()
        }
        configureConfigurations(project, javaPlugin)
        configureTasks(project, intellijExtension)
    }

    private static void configureConfigurations(@NotNull Project project, @NotNull JavaPlugin javaPlugin) {
        def idea = project.configurations.create(IDEA_CONFIGURATION_NAME)
        def ideaPlugins = project.configurations.create(IDEA_PLUGINS_CONFIGURATION_NAME)

        if (javaPlugin.hasProperty('COMPILE_ONLY_CONFIGURATION_NAME')) {
            project.configurations.getByName(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME).extendsFrom idea, ideaPlugins
            project.configurations.getByName(JavaPlugin.TEST_COMPILE_CONFIGURATION_NAME).extendsFrom idea, ideaPlugins
        } else {
            project.configurations.getByName(JavaPlugin.COMPILE_CONFIGURATION_NAME).extendsFrom idea, ideaPlugins
        }
    }

    private static def configureTasks(@NotNull Project project, @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring IntelliJ IDEA gradle plugin")
        configurePatchPluginXmlTask(project, extension)
        configurePrepareSandboxTasks(project, extension)
        configureRunIdeaTask(project, extension)
        configureBuildPluginTask(project)
        configurePublishPluginTask(project, extension)
        configureProcessResources(project)
        configureInstrumentation(project, extension)
        project.afterEvaluate { configureProjectAfterEvaluate(it, extension) }
    }

    private static void configureProjectAfterEvaluate(@NotNull Project project,
                                                      @NotNull IntelliJPluginExtension extension) {
        for (def subproject : project.subprojects) {
            def subprojectExtension = subproject.extensions.findByType(IntelliJPluginExtension)
            if (subprojectExtension) {
                configureProjectAfterEvaluate(subproject, subprojectExtension)
            }
        }

        configureIntellijDependency(project, extension)
        configurePluginDependencies(project, extension)
        configureTestTasks(project, extension)
    }

    private static void configureIntellijDependency(@NotNull Project project,
                                                    @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring IntelliJ IDEA dependency")
        def resolver = new IdeaDependencyManager(extension.intellijRepo ?: DEFAULT_INTELLIJ_REPO)
        def ideaDependency
        if (extension.localPath != null) {
            if (extension.version != null) {
                LOG.warn("Both `localPath` and `version` specified, second would be ignored")
            }
            LOG.info("Using path to locally installed IDE: '${extension.localPath}'")
            ideaDependency = resolver.resolveLocal(project, extension.localPath)
        } else {
            LOG.info("Using IDE from remote repository")
            ideaDependency = resolver.resolveRemote(project, extension.version, extension.type, extension.downloadSources,
                extension.platformGroupId, extension.platformArtifactId)
        }
        extension.ideaDependency = ideaDependency
        LOG.info("IntelliJ IDEA ${ideaDependency.buildNumber} is used for building")
        resolver.register(project, ideaDependency, IDEA_CONFIGURATION_NAME)

        def toolsJar = Jvm.current().toolsJar
        if (toolsJar) {
            project.dependencies.add(JavaPlugin.RUNTIME_CONFIGURATION_NAME, project.files(toolsJar))
        }
    }

    private static void configurePluginDependencies(@NotNull Project project,
                                                    @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring IntelliJ IDEA plugin dependencies")
        def ideVersion = IdeVersion.createIdeVersion(extension.ideaDependency.buildNumber)
        def resolver = new PluginDependencyManager(project.gradle.gradleUserHomeDir.absolutePath, extension.ideaDependency)
        extension.plugins.each {
            LOG.info("Configuring IntelliJ plugin $it")
            if (it instanceof Project) {
                project.dependencies.add(IDEA_PLUGINS_CONFIGURATION_NAME, it)
                it.afterEvaluate {
                    if (it.plugins.findPlugin(IntelliJPlugin) == null) {
                        throw new BuildException("Cannot use $it as a plugin dependency. IntelliJ Plugin is not found." + it.plugins, null)
                    }
                    extension.pluginDependencies.add(new PluginProjectDependency(it))
                    project.tasks.withType(PrepareSandboxTask)*.dependsOn(it.tasks.findByName(PREPARE_SANDBOX_TASK_NAME))
                }
            } else {
                def (pluginId, pluginVersion, channel) = Utils.parsePluginDependencyString(it.toString())
                if (!pluginId) {
                    throw new BuildException("Failed to resolve plugin $it", null)
                }
                def plugin = resolver.resolve(pluginId, pluginVersion, channel)
                if (plugin == null) {
                    throw new BuildException("Failed to resolve plugin $it", null)
                }
                if (ideVersion != null && !plugin.isCompatible(ideVersion)) {
                    throw new BuildException("Plugin $it is not compatible to ${ideVersion.asString()}", null)
                }
                resolver.register(project, plugin, plugin.builtin ? IDEA_CONFIGURATION_NAME : IDEA_PLUGINS_CONFIGURATION_NAME)
                extension.pluginDependencies.add(plugin)
            }
        }
    }

    private static void configurePatchPluginXmlTask(@NotNull Project project,
                                                    @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring patch plugin.xml task")
        project.tasks.create(PATCH_PLUGIN_XML_TASK_NAME, PatchPluginXmlTask).with {
            group = GROUP_NAME
            description = "Patch plugin xml files with corresponding since/until build numbers and version attributes"
            conventionMapping('version', { project.version?.toString() })
            conventionMapping('pluginXmlFiles', { Utils.sourcePluginXmlFiles(project) })
            conventionMapping('destinationDir', { new File(project.buildDir, PLUGIN_XML_DIR_NAME) })
            conventionMapping('sinceBuild', {
                if (extension.updateSinceUntilBuild) {
                    def ideVersion = IdeVersion.createIdeVersion(extension.ideaDependency.buildNumber)
                    return "$ideVersion.baselineVersion.$ideVersion.build".toString()
                }
            })
            conventionMapping('untilBuild', {
                if (extension.updateSinceUntilBuild) {
                    def ideVersion = IdeVersion.createIdeVersion(extension.ideaDependency.buildNumber)
                    return extension.sameSinceUntilBuild ? "${getSinceBuild()}.*".toString()
                            : "$ideVersion.baselineVersion.*".toString()
                }
            })
        }
    }

    private static void configurePrepareSandboxTasks(@NotNull Project project,
                                                     @NotNull IntelliJPluginExtension extension) {
        configurePrepareSandboxTask(project, extension, false)
        configurePrepareSandboxTask(project, extension, true)
    }

    private static void configurePrepareSandboxTask(@NotNull Project project,
                                                    @NotNull IntelliJPluginExtension extension,
                                                    boolean inTest) {
        LOG.info("Configuring prepare IntelliJ sandbox task")
        def taskName = inTest ? PREPARE_TESTING_SANDBOX_TASK_NAME : PREPARE_SANDBOX_TASK_NAME
        project.tasks.create(taskName, PrepareSandboxTask).with {
            group = GROUP_NAME
            description = "Prepare sandbox directory with installed plugin and its dependencies."
            conventionMapping('pluginName', { extension.pluginName })
            conventionMapping('pluginJar', { (project.tasks.findByName(JavaPlugin.JAR_TASK_NAME) as Jar).archivePath })
            conventionMapping('destinationDir', { project.file(Utils.pluginsDir(extension.sandboxDirectory, inTest)) })
            conventionMapping('configDirectory', { project.file(Utils.configDir(extension.sandboxDirectory, inTest)) })
            conventionMapping('librariesToIgnore', { project.files(extension.ideaDependency.jarFiles) })
            conventionMapping('pluginDependencies', { extension.pluginDependencies })
            dependsOn(JavaPlugin.JAR_TASK_NAME)
        }
    }

    private static void configureRunIdeaTask(@NotNull Project project, @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring run IntelliJ task")
        def configureTask = { RunIdeaTask it ->
            it.group = GROUP_NAME
            it.description = "Runs Intellij IDEA with installed plugin."
            it.conventionMapping.map("ideaDirectory", { Utils.ideaSdkDirectory(extension) })
            it.conventionMapping.map("systemProperties", { extension.systemProperties })
            it.conventionMapping.map("requiredPluginIds", { Utils.getPluginIds(project) })
            it.conventionMapping.map("configDirectory", {
                (project.tasks.findByName(PREPARE_SANDBOX_TASK_NAME) as PrepareSandboxTask).getConfigDirectory()
            })
            it.conventionMapping.map("pluginsDirectory", {
                (project.tasks.findByName(PREPARE_SANDBOX_TASK_NAME) as PrepareSandboxTask).getDestinationDir()
            })
            it.conventionMapping.map("systemDirectory", {
                project.file(Utils.systemDir(extension.sandboxDirectory, false))
            })
            it.dependsOn(PREPARE_SANDBOX_TASK_NAME)
        }
        project.tasks.create(RUN_IDE_TASK_NAME, RunIdeaTask).with(configureTask)
        project.tasks.create(RUN_IDEA_TASK_NAME, RunIdeaTask).with(configureTask).doLast {
            LOG.warn("'runIdea' task is deprecated and will be removed in 0.3.0. Use 'runIde' task instead")
        }
    }

    private static void configureInstrumentation(@NotNull Project project, @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring IntelliJ compile tasks")
        def instrumentCode = { extension.instrumentCode && extension.type != 'RS' }
        project.sourceSets.all { SourceSet sourceSet ->
            def instrumentTask = project.tasks.create(sourceSet.getTaskName('instrument', 'code'), IntelliJInstrumentCodeTask)
            instrumentTask.sourceSet = sourceSet
            instrumentTask.with {
                dependsOn sourceSet.classesTaskName
                onlyIf instrumentCode

                conventionMapping('ideaDependency', { extension.ideaDependency })
                conventionMapping('outputDir', { new File(sourceSet.output.classesDir.getParent(), "${sourceSet.name}-instrumented") })
            }

            def updateTask = project.tasks.create('post' + instrumentTask.name.capitalize())
            updateTask.with {
                dependsOn instrumentTask
                onlyIf instrumentCode

                doLast {
                    def oldClassesDir = sourceSet.output.classesDir

                    // Set the classes dir to the new one with the instrumented classes
                    sourceSet.output.classesDir = instrumentTask.outputDir

                    if (sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) {
                        // When we change the output classes directory, Gradle will automatically configure
                        // the test compile tasks to use the instrumented classes. Normally this is fine,
                        // however, it causes problems for Kotlin projects:

                        // The "internal" modifier can be used to restrict access to the same module.
                        // To make it possible to use internal methods from the main source set in test classes,
                        // the Kotlin Gradle plugin adds the original output directory of the Java task
                        // as "friendly directory" which makes it possible to access internal members
                        // of the main module.

                        // This fails when we change the classes dir. The easiest fix is to prepend the
                        // classes from the "friendly directory" to the compile classpath.
                        AbstractCompile testCompile = project.tasks.findByName('compileTestKotlin') as AbstractCompile
                        if (testCompile) {
                            testCompile.classpath = project.files(oldClassesDir, testCompile.classpath)
                        }
                    }
                }
            }

            // Ensure that our task is invoked when the source set is built
            sourceSet.compiledBy(updateTask)
        }
    }

    private static void configureTestTasks(@NotNull Project project, @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring IntelliJ tests tasks")
        project.tasks.withType(Test).each {
            def configDirectory = project.file(Utils.configDir(extension.sandboxDirectory, true))
            def systemDirectory = project.file(Utils.systemDir(extension.sandboxDirectory, true))
            def pluginsDirectory = project.file(Utils.pluginsDir(extension.sandboxDirectory, true))

            it.enableAssertions = true
            it.systemProperties(extension.systemProperties)
            it.systemProperties(Utils.getIdeaSystemProperties(configDirectory, systemDirectory, pluginsDirectory, Utils.getPluginIds(project)))
            it.jvmArgs = Utils.getIdeaJvmArgs(it, it.jvmArgs, Utils.ideaSdkDirectory(extension))
            it.classpath += project.files("$extension.ideaDependency.classes/lib/resources.jar",
                    "$extension.ideaDependency.classes/lib/idea.jar")
            it.outputs.dir(systemDirectory)
            it.outputs.dir(configDirectory)
            it.dependsOn(project.getTasksByName(PREPARE_TESTING_SANDBOX_TASK_NAME, false))
        }
    }

    private static void configureBuildPluginTask(@NotNull Project project) {
        LOG.info("Configuring building IntelliJ IDEA plugin task")
        def prepareSandboxTask = project.tasks.findByName(PREPARE_SANDBOX_TASK_NAME) as PrepareSandboxTask
        Zip zip = project.tasks.create(BUILD_PLUGIN_TASK_NAME, Zip).with {
            description = "Bundles the project as a distribution."
            group = GROUP_NAME
            from { "${prepareSandboxTask.getDestinationDir()}/${prepareSandboxTask.getPluginName()}" }
            into { prepareSandboxTask.getPluginName() }
            dependsOn(prepareSandboxTask)
            conventionMapping.map('baseName', { prepareSandboxTask.getPluginName() })
            it
        }
        Configuration archivesConfiguration = project.configurations.getByName(Dependency.ARCHIVES_CONFIGURATION)
        if (archivesConfiguration) {
            ArchivePublishArtifact zipArtifact = new ArchivePublishArtifact(zip)
            archivesConfiguration.getArtifacts().add(zipArtifact)
            project.getExtensions().getByType(DefaultArtifactPublicationSet.class).addCandidate(zipArtifact)
            project.getComponents().add(new IntelliJPluginLibrary())
        }
    }

    private static void configurePublishPluginTask(@NotNull Project project,
                                                   @NotNull IntelliJPluginExtension extension) {
        LOG.info("Configuring publishing IntelliJ IDEA plugin task")
        project.tasks.create(PUBLISH_PLUGIN_TASK_NAME, PublishTask).with {
            group = GROUP_NAME
            description = "Publish plugin distribution on plugins.jetbrains.com."
            conventionMapping('username', { extension.publish.username })
            conventionMapping('password', { extension.publish.password })
            conventionMapping('channels', { extension.publish.channels })
            conventionMapping('distributionFile', {
                def buildPluginTask = project.tasks.findByName(BUILD_PLUGIN_TASK_NAME) as Zip
                def distributionFile = buildPluginTask?.archivePath
                return distributionFile?.exists() ? distributionFile : null
            })
            dependsOn { project.getTasksByName(BUILD_PLUGIN_TASK_NAME, false) }
        }
    }

    private static void configureProcessResources(@NotNull Project project) {
        LOG.info("Configuring IntelliJ resources task")
        def processResourcesTask = project.tasks.findByName(JavaPlugin.PROCESS_RESOURCES_TASK_NAME) as ProcessResources
        if (processResourcesTask) {
            processResourcesTask.from(project.tasks.findByName(PATCH_PLUGIN_XML_TASK_NAME)) {
                into("META-INF")
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
            }
        }
    }
}
