pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// gradle does NOT auto-expose local.properties as gradle properties, so read the file directly (like Power Extension).
fun getLocalProperty(key: String, file: String = "local.properties"): String? {
    val localProperties = File(file)
    if (!localProperties.isFile) return null
    val properties = java.util.Properties()
    java.io.InputStreamReader(java.io.FileInputStream(localProperties), Charsets.UTF_8).use { properties.load(it) }
    return properties.getProperty(key)
}

val gprUser = getLocalProperty("gpr.user") ?: System.getenv("USERNAME") ?: ""
val gprKey = getLocalProperty("gpr.key") ?: System.getenv("TOKEN") ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext from GitHub Packages
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = gprUser
                password = gprKey
            }
        }
    }
}

rootProject.name = "TrainerBridgeBLE"
include(":app")
