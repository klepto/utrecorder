package dev.klepto.utrecorder.build.uscript

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty

/**
 * An UnrealScript package the module compiles.
 *
 * The name is the package name, and UCC derives it from the directory the sources sit in, so the two are the same
 * thing: `src/main/uscript/UTRecorder` compiles to `UTRecorder.u`.
 */
abstract class UnrealScriptPackage : Named {
    /**
     * The directory holding the `Classes` directory UCC reads the sources from. Defaults to the directory of this
     * name under the source directory.
     */
    abstract val sources: DirectoryProperty

    /**
     * The packages this one compiles against, beyond the ones the installation configures by default. `EditPackages`
     * is ordered, so these are appended before this package and a package of this module that another one depends on
     * belongs here.
     */
    abstract val editPackages: ListProperty<String>
}

/**
 * Configures the UnrealScript packages a module compiles.
 *
 * Every directory under [sourceDirectory] registers a package of its own, so a module that follows the layout needs
 * no configuration at all. The container is there for the cases the convention does not cover - naming the packages a
 * package depends on, or holding sources somewhere else.
 */
abstract class UnrealScriptExtension {
    /**
     * The directory the package directories sit in.
     */
    abstract val sourceDirectory: DirectoryProperty

    /**
     * The recorder home directory the compiler provisions its Unreal Tournament installation into. Not a task
     * input: it holds a materialized game installation, which is neither reproducible nor worth hashing.
     */
    abstract val homeDirectory: DirectoryProperty

    /**
     * The packages to compile. Registered from the layout, so a module configures them with `named`, never `create`.
     */
    abstract val packages: NamedDomainObjectContainer<UnrealScriptPackage>
}
