plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.maxrave.lansync"
        compileSdk = 37
        minSdk = 26
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(projects.domain)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp3.okhttp)
            implementation(libs.coroutines.android)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.body.limit)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.jmdns)
            implementation(libs.bcprov)
            implementation(libs.bcpkix)
        }

        jvmTest.dependencies {
            implementation(projects.data)
            implementation(libs.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
