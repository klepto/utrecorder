package dev.klepto.utrecorder.build.native

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Native binary a module compiles, named after the directory that holds its sources.
 *
 * `src/main/native/uthooks` compiles every `.c` file under it to `uthooks.dll` on Windows and `uthooks.so` on Linux.
 */
abstract class NativeBinary : Named {
    /**
     * Directory holding the C sources. Defaults to the directory of this name under the source directory.
     */
    abstract val sources: DirectoryProperty

    /**
     * Kind of binary the sources compile to. Defaults to [NativeBinaryType.SHARED_LIBRARY].
     */
    abstract val type: Property<NativeBinaryType>

    /**
     * Platforms the binary is built for. Defaults to all of them. A build on any other platform produces no file for
     * the binary.
     */
    abstract val platforms: SetProperty<NativePlatform>
}

/**
 * Native binaries a module compiles, one for every directory under [sourceDirectory].
 *
 * The `utrecorder.native` plugin registers it as `nativeBinaries`. A module declares the [NativeBinary.type] of every
 * binary that is not a shared library.
 */
abstract class NativeExtension {
    /**
     * Directory holding one source directory for every binary. Defaults to `src/main/native`.
     */
    abstract val sourceDirectory: DirectoryProperty

    /**
     * Binaries to compile. They already exist when the module configures them, so a module reaches them with `named`,
     * never `create`.
     */
    abstract val binaries: NamedDomainObjectContainer<NativeBinary>
}
