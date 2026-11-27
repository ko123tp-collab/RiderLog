plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.rider.log"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rider.log"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // 고정 서명 키: 업데이트할 때 지우고 다시 깔 필요 없이(기록 유지) 덮어서 설치되도록.
    // 키 파일과 비밀번호는 저장소에 없고, GitHub Secrets에서 빌드할 때만 꺼내 씀.
    val ksFile = file("rider.keystore")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    val hasKey = ksFile.exists() && !ksPass.isNullOrEmpty()

    signingConfigs {
        if (hasKey) {
            create("fixed") {
                storeFile = ksFile
                storeType = "pkcs12"
                storePassword = ksPass
                keyAlias = "rider"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasKey) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
