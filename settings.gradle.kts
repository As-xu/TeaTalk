pluginManagement {
    repositories {
        // 镜像优先（本机实测 gradle 官方源不可达，腾讯/阿里云镜像可用）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "TeaTalk"
include(":app")
include(":core")
// UX 设计样张（独立可安装 App，仅用于评审配色与版式，不参与正式包）
include(":design")
