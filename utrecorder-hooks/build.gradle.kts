import dev.klepto.utrecorder.build.native.NativeBinaryType.EXECUTABLE
import dev.klepto.utrecorder.build.native.NativePlatform.WINDOWS_X86

plugins {
    id("utrecorder.native")
}

nativeBinaries {
    binaries {
        named("utinject") {
            type = EXECUTABLE
            platforms = setOf(WINDOWS_X86)
        }
    }
}
