package org.jetbrains.intellij

import com.intellij.structure.impl.utils.StringUtil
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.ivy.internal.artifact.DefaultIvyArtifact
import org.gradle.api.tasks.SourceSet
import org.gradle.process.JavaForkOptions
import org.jetbrains.annotations.NotNull
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

import java.util.regex.Pattern

class Utils {
    public static final Pattern VERSION_PATTERN = Pattern.compile('^([A-Z]{2})-([0-9.A-z]+)\\s*$')

    @NotNull
    public static SourceSet mainSourceSet(@NotNull Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention);
        javaConvention.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    }

    @NotNull
    public static SourceSet testSourceSet(@NotNull Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention);
        javaConvention.sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
    }

    @NotNull
    public static DefaultIvyArtifact createJarDependency(File file, String configuration, File baseDir) {
        return createDependency(baseDir, file, configuration, "jar", "jar")
    }

    @NotNull
    public static DefaultIvyArtifact createDirectoryDependency(File file, String configuration, File baseDir) {
        return createDependency(baseDir, file, configuration, "", "directory")
    }

    private static DefaultIvyArtifact createDependency(File baseDir, File file, String configuration, 
                                                       String extension, String type) {
        def relativePath = baseDir.toURI().relativize(file.toURI()).getPath()
        def name = extension ? relativePath - ".$extension" : relativePath
        def artifact = new DefaultIvyArtifact(file, name, extension, type, null)
        artifact.conf = configuration
        return artifact
    }

    @NotNull
    public static Set<File> sourcePluginXmlFiles(@NotNull Project project) {
        pluginXmlFiles(mainSourceSet(project).resources.srcDirs)
    }

    @NotNull
    public static Set<File> outPluginXmlFiles(@NotNull Project project) {
        pluginXmlFiles(mainSourceSet(project).output.files)
    }

    @NotNull
    private static Set<File> pluginXmlFiles(@NotNull Set<File> roots) {
        Set<File> result = new HashSet<>()
        roots.each {
            def pluginXml = new File(it, "META-INF/plugin.xml")
            if (pluginXml.exists()) {
                try {
                    if (parseXml(pluginXml).name() == 'idea-plugin') {
                        result += pluginXml
                    }
                } catch (SAXParseException ignore) {
                    IntelliJPlugin.LOG.warn("Cannot read ${pluginXml}. Skipping.")
                    IntelliJPlugin.LOG.debug("Cannot read ${pluginXml}", ignore)
                }
            }
        }
        result
    }

    @NotNull
    public static Map<String, Object> getIdeaSystemProperties(@NotNull Project project,
                                                              @NotNull Map<String, Object> originalProperties,
                                                              @NotNull IntelliJPluginExtension extension,
                                                              boolean inTests) {
        def properties = new HashMap<String, Object>()
        properties.putAll(originalProperties)
        properties.putAll(extension.systemProperties)
        properties.put("idea.config.path", project.file(configDir(extension, inTests)).path)
        properties.put("idea.system.path", project.file(systemDir(extension, inTests)).path)
        properties.put("idea.plugins.path", project.file(pluginsDir(extension, inTests)).path)
        def pluginId = getPluginId(project)
        if (!properties.containsKey("idea.required.plugins.id") && pluginId != null) {
            properties.put("idea.required.plugins.id", pluginId)
        }
        return properties;
    }

    public static def configDir(@NotNull IntelliJPluginExtension extension, boolean inTests) {
        def suffix = inTests ? "-test" : ""
        "$extension.sandboxDirectory/config$suffix"
    }

    public static def systemDir(@NotNull IntelliJPluginExtension extension, boolean inTests) {
        def suffix = inTests ? "-test" : ""
        "$extension.sandboxDirectory/system$suffix"
    }

    public static def pluginsDir(@NotNull IntelliJPluginExtension extension, boolean inTests) {
        def suffix = inTests ? "-test" : ""
        "$extension.sandboxDirectory/plugins$suffix"
    }

    @NotNull
    public static List<String> getIdeaJvmArgs(@NotNull JavaForkOptions options,
                                              @NotNull List<String> originalArguments,
                                              @NotNull IntelliJPluginExtension extension) {
        if (options.maxHeapSize == null) options.maxHeapSize = "512m"
        if (options.minHeapSize == null) options.minHeapSize = "256m"
        boolean hasPermSizeArg = false
        def result = []
        for (String arg : originalArguments) {
            if (arg.startsWith("-XX:MaxPermSize")) {
                hasPermSizeArg = true
            }
            result += arg
        }

        result += "-Xbootclasspath/a:${ideaSdkDirectory(extension).absolutePath}/lib/boot.jar"
        if (!hasPermSizeArg) result += "-XX:MaxPermSize=250m"
        return result
    }

    @NotNull
    public static File ideaSdkDirectory(@NotNull IntelliJPluginExtension extension) {
        def path = extension.alternativeIdePath
        if (path) {
            def dir = new File(path);
            if (dir.getName().endsWith(".app")) {
                dir = new File(dir, "Contents")
            }
            if (!dir.exists()) {
                def ideaDirectory = extension.ideaDependency.classes
                IntelliJPlugin.LOG.error("Cannot find alternate SDK path: $dir. Default IDEA will be used : $ideaDirectory")
                return ideaDirectory
            }
            return dir
        }
        return extension.ideaDependency.classes
    }

    @NotNull
    public static String ideaBuildNumber(@NotNull File ideaDirectory) {
        if (isMac()) {
            def file = new File(ideaDirectory, "Resources/build.txt")
            if (file.exists()) {
                return file.getText('UTF-8').trim()
            }
        }
        return new File(ideaDirectory, "build.txt").getText('UTF-8').trim()
    }

    public static boolean isMac() {
        return System.getProperty("os.name").toLowerCase(Locale.US).startsWith("mac")
    }

    static def getPluginId(@NotNull Project project) {
        Set<String> ids = new HashSet<>()
        sourcePluginXmlFiles(project).each {
            def pluginXml = parseXml(it)
            ids += pluginXml.id*.text()
        }
        return ids.size() == 1 ? ids.first() : null;
    }

    static Node parseXml(File file) {
        def parser = new XmlParser(false, true, true)
        parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        parser.setErrorHandler(new ErrorHandler() {
            @Override
            void warning(SAXParseException e) throws SAXException {

            }

            @Override
            void error(SAXParseException e) throws SAXException {
                throw e
            }

            @Override
            void fatalError(SAXParseException e) throws SAXException {
                throw e
            }
        })
        return parser.parse(file)
    }

    public static boolean isJarFile(@NotNull File file) {
        return StringUtil.endsWithIgnoreCase(file.name, ".jar")
    }

    public static boolean isZipFile(@NotNull File file) {
        return StringUtil.endsWithIgnoreCase(file.name, ".zip")
    }

    @NotNull
    public static parsePluginDependencyString(@NotNull String s) {
        def id = null, version = null, channel = null
        def idAndVersion = s.split('[:]', 2)
        if (idAndVersion.length == 1) {
            def idAndChannel = idAndVersion[0].split('[@]', 2)
            id = idAndChannel[0]
            channel = idAndChannel.length > 1 ? idAndChannel[1] : null
        } else if (idAndVersion.length == 2) {
            def versionAndChannel = idAndVersion[1].split('[@]', 2)
            id = idAndVersion[0]
            version = versionAndChannel[0]
            channel = versionAndChannel.length > 1 ? versionAndChannel[1] : null
        }
        return new Tuple(id ?: null, version ?: null, channel ?: null)
    }
}
