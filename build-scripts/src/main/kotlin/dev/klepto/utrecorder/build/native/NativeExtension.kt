package dev.klepto.utrecorder.build.native

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * A native binary the module compiles. The name is the base name of the binary and the directory the sources sit in:
 * `src/main/native/uthooks` compiles to `uthooks.dll` on Windows and `uthooks.so` on Linux, from every `.c` file under
 * that directory.
 */
abstract class NativeBinary : Named {
    /**
     * The directory holding the C sources. Defaults to the directory of this name under the source directory.
     */
    abstract val sources: DirectoryProperty

    /**
     * What the sources compile to. Defaults to [NativeBinaryType.SHARED_LIBRARY], which is what a hook is.
     */
    abstract val type: Property<NativeBinaryType>

    /**
     * The platforms this binary is built for. Defaults to all of them.
     */
    abstract val platforms: SetProperty<NativePlatform>
}

/**
 * Configures the native binaries a module compiles. Every directory under [sourceDirectory] registers a binary of its
 * own, and a module declares the [NativeBinary.type] of anything that is not a shared library.
 */
abstract class NativeExtension {
    /**
     * The directory the binary directories sit in.
     */
    abstract val sourceDirectory: DirectoryProperty

    /**
     * The binaries to compile. Registered from the layout, so a module configures them with `named`, never `create`.
     */
    abstract val binaries: NamedDomainObjectContainer<NativeBinary>
}
