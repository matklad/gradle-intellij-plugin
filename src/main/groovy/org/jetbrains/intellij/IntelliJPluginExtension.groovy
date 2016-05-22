package org.jetbrains.intellij

import org.jetbrains.intellij.dependency.IdeaDependency
import org.jetbrains.intellij.dependency.PluginDependency

@SuppressWarnings("GroovyUnusedDeclaration")
class IntelliJPluginExtension {
    String[] plugins
    String version
    String type
    String pluginName
    String sandboxDirectory
    String intellijRepo
    String alternativeIdePath
    boolean instrumentCode
    boolean updateSinceUntilBuild
    boolean sameSinceUntilBuild
    boolean downloadSources
    Publish publish

    IdeaDependency ideaDependency
    private final Set<PluginDependency> pluginDependencies = new HashSet<>();
    private final Map<String, Object> systemProperties = new HashMap<>();

    String getType() {
        return version.startsWith("IU-") || "IU".equals(type) ? "IU" : "IC"
    }

    String getVersion() {
        return version.startsWith("IU-") || version.startsWith("IC-") ? version.substring(3) : version
    }

    Set<PluginDependency> getPluginDependencies() {
        return pluginDependencies
    }

    def publish(Closure c) {
        publish.with(c)
    }

    public static class Publish {
        String pluginId
        String username
        String password
        String channel

        def pluginId(String pluginId) {
            this.pluginId = pluginId
        }

        def username(String username) {
            this.username = username
        }

        def password(String password) {
            this.password = password
        }

        def channel(String channel) {
            this.channel = channel
        }
    }

    Map<String, Object> getSystemProperties() {
        systemProperties
    }

    void setSystemProperties(Map<String, ?> properties) {
        systemProperties.clear()
        systemProperties.putAll(properties)
    }

    IntelliJPluginExtension systemProperties(Map<String, ?> properties) {
        systemProperties.putAll(properties)
        this
    }

    IntelliJPluginExtension systemProperty(String name, Object value) {
        systemProperties.put(name, value)
        this
    }
}
