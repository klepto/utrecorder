import dev.klepto.utrecorder.build.native.NativeBinaryType
import dev.klepto.utrecorder.build.native.NativePlatform

plugins {
    id("utrecorder.native")
}

nativeBinaries {
    binaries {
        named("utinject") {
            type = NativeBinaryType.EXECUTABLE
            platforms = setOf(NativePlatform.WINDOWS_X86)
        }
    }
}
