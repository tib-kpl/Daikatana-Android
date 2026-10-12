plugins {
    id("com.android.application")
}

// Embarques dans l'APK (assets) :
//   port/     moteur du port PortMaster (../../daikatana) : binaire, libs, gamedata, cfgs
//   runtime/  rootfs Linux arm64 (runtime/out/rootfs.tgz : extension .tgz car AGP decompresse
//             les assets .gz ; voir runtime/README.md)
abstract class SyncPortAssets : DefaultTask() {
    @get:InputDirectory abstract val portDir: DirectoryProperty
    @get:InputFile abstract val autoexec: RegularFileProperty
    @get:InputFile abstract val rootfs: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun run() {
        fs.sync {
            from(portDir) {
                include("daikatana", "libs.aarch64/**", "gamedata/**", "cfgs/**")
                exclude("cfgs/autoexec.cfg")
                into("port")
                // Mesa fournit l'OpenGL de bureau ; le mode GLES du port (lu avant autoexec.cfg)
                // donne des textures corrompues.
                filesMatching("cfgs/daikatana13.cfg") {
                    filter { line -> line.replace("set gl_forcegles \"2\"", "set gl_forcegles \"0\"") }
                }
            }
            from(autoexec) { into("port/cfgs") }
            from(rootfs) { into("runtime") }
            into(outputDir)
        }
    }
}

val syncPortAssets = tasks.register<SyncPortAssets>("syncPortAssets") {
    portDir.set(rootProject.layout.projectDirectory.dir("../daikatana"))
    autoexec.set(rootProject.layout.projectDirectory.file("runtime/autoexec.cfg"))
    rootfs.set(rootProject.layout.projectDirectory.file("runtime/out/rootfs.tgz"))
}

android {
    namespace = "fr.tibkpl.daikatana"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.tibkpl.openkatana"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("OPENKATANA_VERSION_CODE") ?: "3").toInt()
        versionName = System.getenv("OPENKATANA_VERSION_NAME") ?: "0.3"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Signature de l'APK release : cle fournie par variables d'environnement (secrets GitHub,
    // voir README) ; a defaut, cle de debug (APK installable mais non mis a jour d'une
    // version a l'autre si la cle change).
    val keystore = System.getenv("OPENKATANA_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("OPENKATANA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENKATANA_KEY_ALIAS")
                keyPassword = System.getenv("OPENKATANA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // proot et son loader doivent exister en fichiers executables (nativeLibraryDir)
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += listOf("pak", "tgz")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncPortAssets, SyncPortAssets::outputDir)
    }
}
