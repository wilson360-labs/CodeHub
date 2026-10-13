// Módulo :echo — reproductor completo de Echo Nightly injertado en CodeHub.
//
// Se importa el árbol de código de itsmechinmoy/echo-nightly (app/src/main)
// SIN cambios en la lógica, solo el manifiesto se adapta a módulo librería
// (namespace propio, sin launcher) y el arranque (Koin) se delega desde
// CodeHubApp. El contrato de extensiones es el AAR publicado
// dev.brahmkshatriya.echo:common:1.0.0 (la misma versión que declara el
// módulo :common del fork), así que no se compila el módulo KMP.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.brahmkshatriya.echo"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 < 26: mismas APIs java.time que el resto de CodeHub.
        coreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Contrato de extensiones (mismo artefacto que ya usa CodeHub; se excluye
    // protobuf-java porque el AAR no lo referencia y chocaría con el
    // protobuf-javalite del stack Appodeal/AdMob).
    implementation("dev.brahmkshatriya.echo:common:1.0.0") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }

    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // androidx
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.core:core-google-shortcuts:1.1.0")
    implementation("com.google.android.material:material:1.14.0-alpha04")

    // Paging + cache
    implementation("androidx.paging:paging-common-ktx:3.3.6")
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("com.mayakapps.kache:file-kache:2.1.1")

    // Room (persistencia de descargas / extensiones)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Koin (DI) + WorkManager factory (Echo elimina el initializer por defecto)
    implementation("io.insert-koin:koin-android:4.1.0")
    implementation("io.insert-koin:koin-androidx-startup:4.1.0")
    implementation("io.insert-koin:koin-androidx-workmanager:4.1.0")

    // Media3 (misma versión que CodeHub y el fork)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")

    // Coil 3 (imágenes)
    implementation("io.coil-kt.coil3:coil:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // Widgets / UI auxiliar (JitPack)
    implementation("com.github.madrapps:pikolo:2.0.2")
    implementation("com.github.bosphere.android-fadingedgelayout:fadingedgelayout:1.0.0")
    implementation("me.zhanghai.android.fastscroll:library:1.3.0")
    implementation("com.flaviofaria:kenburnsview:1.0.7")
    implementation("com.telefonica:nestedscrollwebview:0.1.6")
    implementation("com.github.acsbendi:Android-Request-Inspector-WebView:1.0.12")

    // Red / serialización que expone common
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
