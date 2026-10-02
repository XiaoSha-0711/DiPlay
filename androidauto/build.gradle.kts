// Android Auto receiver, vendored from Open Headunit (https://github.com/andreknieriem/open-headunit,
// AGPL-3.0, see LICENSE and COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt in this directory).
plugins {
    id("com.android.library")
}

// Android Auto's head-unit certificate and key. Open Headunit publishes them in its repository; this
// one keeps private keys out of Git, so the build fetches them from that pinned commit and checks them.
val headUnitIdentityDir = layout.buildDirectory.dir("generated/headunit-identity/res")
val headUnitIdentity = mapOf(
    "cert" to "851d80c86bf469a3dd121b7a7084ca97b91b3d2cc72922c451e0156b455f38c1",
    "privkey" to "015d172a6e4d5b7f04a5c820a60a41673f99d6e01c2f658e2a0909efbf6d49f7",
)
val fetchHeadUnitIdentity by tasks.registering {
    val outputDir = headUnitIdentityDir
    outputs.dir(outputDir)
    doLast {
        val raw = outputDir.get().asFile.resolve("raw").apply { mkdirs() }
        headUnitIdentity.forEach { (name, sha256) ->
            val target = raw.resolve(name)
            fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (target.isFile && digest(target.readBytes()) == sha256) return@forEach
            val url = "https://raw.githubusercontent.com/andreknieriem/open-headunit/" +
                "ebee4ce4d8666a5a727ee042ac7e8ecb5af8e17f/app/src/main/res/raw/$name"
            val bytes = java.net.URI(url).toURL().openStream().use { it.readBytes() }
            check(digest(bytes) == sha256) { "Android Auto head-unit $name does not match its pinned checksum" }
            target.writeBytes(bytes)
        }
    }
}

android {
    namespace = "com.andrerinas.openheadunit"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        // The app module's version is not visible to a library, so Open Headunit's own is kept.
        buildConfigField("String", "VERSION_NAME", "\"3.5.0-beta2\"")
        buildConfigField("int", "VERSION_CODE", "115")
        buildConfigField("String", "FLAVOR", "\"github\"")
        buildConfigField("String", "GIT_SHA", "\"ebee4ce4d866\"")
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales()}\"")
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets.getByName("main").res.srcDir(headUnitIdentityDir)

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
    }
}


fun availableLocales(): String = file("src/main/res").listFiles { dir ->
    dir.isDirectory && dir.name.startsWith("values-") && dir.resolve("strings.xml").exists() &&
        !dir.name.contains("night") && !dir.name.contains("land") && !dir.name.contains("port") &&
        !dir.name.matches(Regex("values-[whsml]\\d+.*")) && !dir.name.matches(Regex("values-v\\d+"))
}?.map { it.name.removePrefix("values-") }?.sorted()?.joinToString(",") ?: ""

dependencies {
    implementation("org.conscrypt:conscrypt-android:2.6.1")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

tasks.named("preBuild") { dependsOn(fetchHeadUnitIdentity) }
