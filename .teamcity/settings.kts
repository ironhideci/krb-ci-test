import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.schedule
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

version = "2024.03"

project {
    description = "Kingdom Rush: Battles - Multi-platform game builds"
    
    vcsRoot(KingdomRushBattlesGit)
    
    // Dev Environment
    subProject(DevEnvironment)
    
    // QA Environment
    subProject(QAEnvironment)
    
    // Prod Environment
    subProject(ProdEnvironment)
    
    // Shared parameters
    params {
        param("env.BUILD_SCRIPTS_DIR", "build-scripts")
        param("env.S3_BUCKET", "krb-builds")
        password("env.SLACK_WEBHOOK", "credentialsJSON:slack-webhook-krb")
        password("env.AWS_ACCESS_KEY_ID", "credentialsJSON:aws-access-key")
        password("env.AWS_SECRET_ACCESS_KEY", "credentialsJSON:aws-secret-key")
        password("env.GOOGLE_PLAY_KEY", "credentialsJSON:google-play-service-account")
        password("env.APPLE_ID", "credentialsJSON:apple-id")
        password("env.APPLE_APP_SPECIFIC_PASSWORD", "credentialsJSON:apple-app-password")
    }
}

object KingdomRushBattlesGit : GitVcsRoot({
    name = "Kingdom Rush Battles Git"
    url = "git@github.com:ironhidegames/kingdom-rush-battles.git"
    branch = "refs/heads/dev"
    branchSpec = """
        +:refs/heads/dev
        +:refs/heads/prod
        +:refs/heads/qa
    """.trimIndent()
    authMethod = uploadedKey {
        userName = "git"
        uploadedKey = "krb-deploy-key"
    }
})

// Dev Environment Project
object DevEnvironment : Project({
    name = "Dev Environment"
    description = "Development builds - Nightly + on push to dev branch"
    
    buildType(DevAppleIOS)
    buildType(DevAppleTVOS)
    buildType(DevAppleMac)
    buildType(DevGoogleAndroid)
    buildType(DevSteamWindows)
    buildType(DevSteamMac)
    
    params {
        param("env.ENVIRONMENT", "dev")
        param("env.CHEATS_ENABLED", "false")
    }
})

// QA Environment Project
object QAEnvironment : Project({
    name = "QA Environment"
    description = "QA builds - Manual trigger with cheats enabled"
    
    buildType(QAAppleIOS)
    buildType(QAAppleTVOS)
    buildType(QAAppleMac)
    buildType(QAGoogleAndroid)
    buildType(QASteamWindows)
    buildType(QASteamMac)
    
    params {
        param("env.ENVIRONMENT", "qa")
        param("env.CHEATS_ENABLED", "true")
    }
})

// Prod Environment Project
object ProdEnvironment : Project({
    name = "Prod Environment"
    description = "Production builds - Auto-submit to stores on push to prod branch"
    
    buildType(ProdAppleIOS)
    buildType(ProdAppleTVOS)
    buildType(ProdAppleMac)
    buildType(ProdGoogleAndroid)
    buildType(ProdSteamWindows)
    buildType(ProdSteamMac)
    
    params {
        param("env.ENVIRONMENT", "prod")
        param("env.CHEATS_ENABLED", "false")
        param("env.TESTFLIGHT_BUILD_ODD", "true")
    }
})

// Template for platform-specific builds
abstract class PlatformBuild(
    val environment: String,
    val store: String,
    val platform: String,
    val branch: String = "dev"
) : BuildType({
    name = "$environment-$store-$platform"
    
    vcs {
        root(KingdomRushBattlesGit)
        branchFilter = "+:refs/heads/$branch"
    }
    
    params {
        param("env.STORE", store)
        param("env.PLATFORM", platform)
    }
    
    steps {
        script {
            name = "Build"
            scriptContent = """
                #!/bin/bash
                set -e
                
                export ENVIRONMENT=%env.ENVIRONMENT%
                export STORE=%env.STORE%
                export PLATFORM=%env.PLATFORM%
                export BUILD_NUMBER=%build.number%
                export CHEATS=%env.CHEATS_ENABLED%
                
                echo "Building ${'$'}ENVIRONMENT ${'$'}STORE ${'$'}PLATFORM build ${'$'}BUILD_NUMBER"
                
                python3 ${'$'}{BUILD_SCRIPTS_DIR}/build.py \
                  --env ${'$'}{ENVIRONMENT} \
                  --store ${'$'}{STORE} \
                  --platform ${'$'}{PLATFORM} \
                  --build-number ${'$'}{BUILD_NUMBER} \
                  --cheats=${'$'}{CHEATS}
            """.trimIndent()
        }
        
        script {
            name = "Upload to S3"
            scriptContent = """
                #!/bin/bash
                set -e
                
                python3 %env.BUILD_SCRIPTS_DIR%/upload_s3.py \
                  --env %env.ENVIRONMENT% \
                  --store %env.STORE% \
                  --platform %env.PLATFORM% \
                  --build-number %build.number%
            """.trimIndent()
        }
        
        if (environment != "prod") {
            script {
                name = "Generate Install Packages"
                scriptContent = """
                    #!/bin/bash
                    set -e
                    
                    python3 %env.BUILD_SCRIPTS_DIR%/package.py \
                      --env %env.ENVIRONMENT% \
                      --platform %env.PLATFORM% \
                      --type adhoc
                """.trimIndent()
            }
        }
        
        if (environment == "prod") {
            script {
                name = "Submit to Stores"
                scriptContent = """
                    #!/bin/bash
                    set -e
                    
                    python3 %env.BUILD_SCRIPTS_DIR%/dist.py \
                      --submit \
                      --store %env.STORE% \
                      --platform %env.PLATFORM% \
                      --build-number %build.number% \
                      --track rc
                """.trimIndent()
            }
            
            script {
                name = "Create Release Branch"
                scriptContent = """
                    #!/bin/bash
                    set -e
                    
                    VERSION=${'$'}(cat version.txt)
                    BRANCH="prod-v${'$'}{VERSION}.%build.number%"
                    
                    git checkout -b ${'$'}{BRANCH}
                    git add .
                    git commit -m "Release build %build.number%" || true
                    git push origin ${'$'}{BRANCH}
                """.trimIndent()
            }
        }
        
        script {
            name = "Slack Notification"
            scriptContent = """
                #!/bin/bash
                
                if [ "%env.ENVIRONMENT%" = "prod" ]; then
                  VERSION=${'$'}(cat version.txt 2>/dev/null || echo "unknown")
                  MESSAGE="🚀 *Prod Build %build.number%* deployed\nStore: %env.STORE%\nPlatform: %env.PLATFORM%\nBranch: prod-v${'$'}{VERSION}.%build.number%"
                else
                  MESSAGE="✅ *%env.ENVIRONMENT% Build %build.number%* complete\nStore: %env.STORE%\nPlatform: %env.PLATFORM%"
                fi
                
                curl -X POST -H 'Content-type: application/json' \
                  --data "{\"text\":\"${'$'}{MESSAGE}\"}" \
                  %env.SLACK_WEBHOOK%
            """.trimIndent()
        }
    }
    
    // Triggers
    if (environment == "dev") {
        triggers {
            vcs {
                branchFilter = "+:refs/heads/dev"
            }
            schedule {
                schedulingPolicy = daily {
                    hour = 3
                    minute = 0
                }
                branchFilter = "+:refs/heads/dev"
                triggerBuild = always()
            }
        }
    }
    
    if (environment == "prod") {
        triggers {
            vcs {
                branchFilter = "+:refs/heads/prod"
            }
        }
    }
    
    // QA has no automatic triggers - manual only
    
    requirements {
        if (store == "apple") {
            contains("teamcity.agent.jvm.os.name", "Mac")
        }
    }
})

// Dev builds
object DevAppleIOS : PlatformBuild("Dev", "apple", "ios", "dev")
object DevAppleTVOS : PlatformBuild("Dev", "apple", "tvos", "dev")
object DevAppleMac : PlatformBuild("Dev", "apple", "macos", "dev")
object DevGoogleAndroid : PlatformBuild("Dev", "google", "android", "dev")
object DevSteamWindows : PlatformBuild("Dev", "steam", "windows", "dev")
object DevSteamMac : PlatformBuild("Dev", "steam", "macos", "dev")

// QA builds
object QAAppleIOS : PlatformBuild("QA", "apple", "ios", "qa")
object QAAppleTVOS : PlatformBuild("QA", "apple", "tvos", "qa")
object QAAppleMac : PlatformBuild("QA", "apple", "macos", "qa")
object QAGoogleAndroid : PlatformBuild("QA", "google", "android", "qa")
object QASteamWindows : PlatformBuild("QA", "steam", "windows", "qa")
object QASteamMac : PlatformBuild("QA", "steam", "macos", "qa")

// Prod builds
object ProdAppleIOS : PlatformBuild("Prod", "apple", "ios", "prod")
object ProdAppleTVOS : PlatformBuild("Prod", "apple", "tvos", "prod")
object ProdAppleMac : PlatformBuild("Prod", "apple", "macos", "prod")
object ProdGoogleAndroid : PlatformBuild("Prod", "google", "android", "prod")
object ProdSteamWindows : PlatformBuild("Prod", "steam", "windows", "prod")
object ProdSteamMac : PlatformBuild("Prod", "steam", "macos", "prod")
