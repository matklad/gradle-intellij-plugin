package org.jetbrains.intellij.dependency

import com.google.common.base.Predicates
import com.intellij.structure.domain.IdeVersion
import com.intellij.structure.impl.utils.JarsUtils
import groovy.transform.ToString
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.intellij.Utils

@ToString
public class PluginDependency implements Serializable {
    @NotNull
    private String id
    @NotNull
    private String version
    @Nullable
    private String channel

    @Nullable
    private String sinceBuild
    @Nullable
    private String untilBuild

    @Nullable
    private File classesDirectory
    @Nullable
    private File metaInfDirectory
    @NotNull
    private File artifact
    @NotNull
    private Collection<File> jarFiles = Collections.emptySet()

    boolean builtin

    PluginDependency(@NotNull String id, @NotNull String version, @NotNull File artifact, boolean builtin = false) {
        this.id = id
        this.version = version
        this.artifact = artifact
        this.builtin = builtin
        initFiles()
    }

    private def initFiles() {
        if (Utils.isJarFile(artifact)) {
            jarFiles = Collections.singletonList(artifact)
        }
        if (artifact.isDirectory()) {
            File lib = new File(artifact, "lib");
            if (lib.isDirectory()) {
                jarFiles = JarsUtils.collectJars(lib, Predicates.<File> alwaysTrue(), true)
            }
            File classes = new File(artifact, "classes");
            if (classes.isDirectory()) {
                classesDirectory = classes
            }
            File metaInf = new File(artifact, "META-INF");
            if (metaInf.isDirectory()) {
                metaInfDirectory = metaInf
            }
        }
    }

    def boolean isCompatible(@NotNull IdeVersion ideVersion) {
        return sinceBuild == null ||
                IdeVersion.createIdeVersion(sinceBuild) <= ideVersion &&
                (untilBuild == null || ideVersion <= IdeVersion.createIdeVersion(untilBuild));
    }

    def String getFqn() {
        return pluginFqn(id, version, channel)
    }

    def static pluginFqn(@NotNull String id, @NotNull String version, @Nullable String channel) {
        "$id-${channel ?: 'master'}-$version"
    }

    String getId() {
        return id
    }

    void setId(String id) {
        this.id = id
    }

    String getVersion() {
        return version
    }

    void setVersion(String version) {
        this.version = version
    }

    String getChannel() {
        return channel
    }

    void setChannel(String channel) {
        this.channel = channel
    }

    String getSinceBuild() {
        return sinceBuild
    }

    void setSinceBuild(String sinceBuild) {
        this.sinceBuild = sinceBuild
    }

    String getUntilBuild() {
        return untilBuild
    }

    void setUntilBuild(String untilBuild) {
        this.untilBuild = untilBuild
    }

    File getArtifact() {
        return artifact
    }

    void setArtifact(File artifact) {
        this.artifact = artifact
    }

    Collection<File> getJarFiles() {
        return jarFiles
    }

    void setJarFiles(Collection<File> jarFiles) {
        this.jarFiles = jarFiles
    }

    @Nullable
    File getClassesDirectory() {
        return classesDirectory
    }

    void setClassesDirectory(@Nullable File classesDirectory) {
        this.classesDirectory = classesDirectory
    }

    @Nullable
    File getMetaInfDirectory() {
        return metaInfDirectory
    }

    void setMetaInfDirectory(@Nullable File metaInfDirectory) {
        this.metaInfDirectory = metaInfDirectory
    }

    boolean getBuiltin() {
        return builtin
    }

    void setBuiltin(boolean builtin) {
        this.builtin = builtin
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (!(o instanceof PluginDependency)) return false

        PluginDependency that = (PluginDependency) o

        if (builtin != that.builtin) return false
        if (artifact != that.artifact) return false
        if (channel != that.channel) return false
        if (classesDirectory != that.classesDirectory) return false
        if (id != that.id) return false
        if (jarFiles != that.jarFiles) return false
        if (metaInfDirectory != that.metaInfDirectory) return false
        if (sinceBuild != that.sinceBuild) return false
        if (untilBuild != that.untilBuild) return false
        if (version != that.version) return false

        return true
    }

    int hashCode() {
        int result
        result = id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + (channel != null ? channel.hashCode() : 0)
        result = 31 * result + (sinceBuild != null ? sinceBuild.hashCode() : 0)
        result = 31 * result + (untilBuild != null ? untilBuild.hashCode() : 0)
        result = 31 * result + (classesDirectory != null ? classesDirectory.hashCode() : 0)
        result = 31 * result + (metaInfDirectory != null ? metaInfDirectory.hashCode() : 0)
        result = 31 * result + artifact.hashCode()
        result = 31 * result + jarFiles.hashCode()
        result = 31 * result + (builtin ? 1 : 0)
        return result
    }
}
