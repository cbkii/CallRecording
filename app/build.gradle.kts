import com.android.builder.internal.packaging.IncrementalPackager
import com.android.tools.build.apkzlib.sign.SigningExtension
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zfile.ZFiles
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFileOptions
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.X509Certificate

plugins {
    id("com.android.application")
}

val ksPath: String = System.getenv("KEYSTORE_PATH") ?: ""

android {
    enableKotlin = false
    namespace = "io.github.vvb2060.callrecording"
    if (ksPath.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        versionCode = 6
        versionName = "2.0"
        externalNativeBuild {
            ndkBuild {
                abiFilters += listOf("arm64-v8a")
                abiFilters += listOf("armeabi-v7a", "x86", "x86_64")
                arguments += "-j${Runtime.getRuntime().availableProcessors()}"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles("proguard-rules.pro")
            signingConfig = if (ksPath.isNotEmpty()) signingConfigs["release"] else signingConfigs["debug"]
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.3.0")
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("io.github.libxposed:api:101.0.0")
}

val optimizeReleaseRes by tasks.registering(Exec::class) {
    val aapt2 = project.androidComponents.sdkComponents.aapt2.get().executable.get().toString()
    val zip = Paths.get(
        project.layout.buildDirectory.get().toString(), "intermediates",
        "optimized_processed_res", "release", "optimizeReleaseResources",
        "resources-release-optimize.ap_"
    )
    val optimized = zip.resolveSibling("optimized")
    commandLine(
        aapt2, "optimize", "--collapse-resource-names",
        "--enable-sparse-encoding", "-o", optimized, zip
    )

    doLast {
        Files.delete(zip)
        Files.move(optimized, zip)
    }
}

val delMetadata by tasks.registering {
    val sign = if (ksPath.isNotEmpty()) android.signingConfigs["release"]!! else android.signingConfigs["debug"]
    val minSdk = android.defaultConfig.minSdk!!
    val files = tasks.named("packageRelease").get().outputs.files
    doLast {
        val options = ZFileOptions().apply {
            alignmentRule = AlignmentRules.constantForSuffix(".so", 16 * 1024)
            noTimestamps = true
            autoSortFiles = true
        }
        val apk = files.asFileTree.filter { it.name.endsWith(".apk") }.singleFile
        ZFiles.apk(apk, options).use { zFile ->
            val keyStore = KeyStore.getInstance(sign.storeType ?: KeyStore.getDefaultType())
            FileInputStream(sign.storeFile!!).use {
                keyStore.load(it, sign.storePassword!!.toCharArray())
            }
            val protParam = KeyStore.PasswordProtection(sign.keyPassword!!.toCharArray())
            val entry = keyStore.getEntry(sign.keyAlias!!, protParam)
            val privateKey = entry as KeyStore.PrivateKeyEntry
            val signingOptions = SigningOptions.builder()
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setKey(privateKey.privateKey)
                .setCertificates(privateKey.certificate as X509Certificate)
                .setValidation(SigningOptions.Validation.ASSUME_INVALID)
                .build()
            SigningExtension(signingOptions).register(zFile)
            zFile.get(IncrementalPackager.APP_METADATA_ENTRY_PATH)?.delete()
        }
    }
}

tasks.configureEach {
    if (name == "optimizeReleaseResources") {
        finalizedBy(optimizeReleaseRes)
    }
    if (name == "packageRelease") {
        finalizedBy(delMetadata)
    }
}
