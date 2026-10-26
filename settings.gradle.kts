// Gradle 仓库策略：
// 1) 官方源优先（本机实测 dl.google.com / repo1.maven.org 可达）；
// 2) 阿里云镜像作为兜底，且只接“google/public”两组；
// 3) 不把镜像放在前面：阿里云 public 偶尔对个别构件返回 502，
//    一旦命中会让整个仓库被 Gradle 禁用（这次 :design 构建失败的根因）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 兜底镜像（仅当官方源失败时才会被访问）
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.google\\.android.*")
            }
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin.*")
                includeGroupByRegex("org\\.jetbrains\\.kotlinx.*")
                includeGroupByRegex("com\\.google\\.devtools\\.ksp")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.google\\.android.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin.*")
                includeGroupByRegex("org\\.jetbrains\\.kotlinx.*")
                includeGroupByRegex("com\\.google\\.devtools\\.ksp")
            }
        }
    }
}

rootProject.name = "TeaTalk"
include(":app")
include(":core")
// UX 设计样张（独立可安装 App，仅用于评审配色与版式，不参与正式包）
include(":design")
