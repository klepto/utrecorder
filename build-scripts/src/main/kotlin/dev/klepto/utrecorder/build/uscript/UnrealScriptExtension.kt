package dev.klepto.utrecorder.build.uscript

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty

/**
 * An UnrealScript package the module compiles. The name is the package name and the directory the sources sit in:
 * `src/main/uscript/UTRecorder` compiles to `UTRecorder.u`.
 */
abstract class UnrealScriptPackage : Named {
    /**
     * The directory holding the `Classes` directory UCC reads the sources from. Defaults to the directory of this
     * name under the source directory.
     */
    abstract val sources: DirectoryProperty

    /**
     * The packages this one compiles against, beyond the ones the installation configures by default. They are
     * appended to `EditPackages` before this package, which is what a package of this module depends on.
     */
    abstract val editPackages: ListProperty<String>
}

/**
 * Configures the UnrealScript packages a module compiles. Every directory under [sourceDirectory] registers a package
 * of its own, and [packages] names what the layout cannot say, such as the packages a package compiles against.
 */
abstract class UnrealScriptExtension {
    /**
     * The directory the package directories sit in.
     */
    abstract val sourceDirectory: DirectoryProperty

    /**
     * The recorder home directory the compiler provisions its Unreal Tournament installation into. Not a task input.
     */
    abstract val homeDirectory: DirectoryProperty

    /**
     * The packages to compile. Registered from the layout, so a module configures them with `named`, never `create`.
     */
    abstract val packages: NamedDomainObjectContainer<UnrealScriptPackage>
}
