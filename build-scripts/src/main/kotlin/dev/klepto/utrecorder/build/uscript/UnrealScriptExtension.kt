package dev.klepto.utrecorder.build.uscript

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty

/**
 * UnrealScript package a module compiles, named after the directory that holds its sources.
 *
 * `src/main/uscript/UTRecorder` compiles to `UTRecorder.u`.
 */
abstract class UnrealScriptPackage : Named {
    /**
     * Directory holding the `Classes` directory UCC reads the sources from. Defaults to the directory of this name
     * under the source directory.
     */
    abstract val sources: DirectoryProperty

    /**
     * Packages this package compiles against, beyond the ones the installation configures by default. They are added
     * to `EditPackages` ahead of this package.
     */
    abstract val editPackages: ListProperty<String>
}

/**
 * UnrealScript packages a module compiles, one for every directory under [sourceDirectory].
 *
 * The `utrecorder.unrealscript` plugin registers it as `unrealscript`. A module declares the
 * [UnrealScriptPackage.editPackages] of every package that compiles against another one.
 */
abstract class UnrealScriptExtension {
    /**
     * Directory holding one source directory for every package. Always `src/main/uscript`; a module cannot change it.
     */
    abstract val sourceDirectory: DirectoryProperty

    /**
     * Recorder home directory the compiler provisions its Unreal Tournament installation into. Defaults to
     * `.utrecorder` in the repository. A home set through `utrecorder.home` or `UTRECORDER_HOME` is used instead.
     */
    abstract val homeDirectory: DirectoryProperty

    /**
     * Packages to compile. They already exist when the module configures them, so a module reaches them with `named`,
     * never `create`.
     */
    abstract val packages: NamedDomainObjectContainer<UnrealScriptPackage>
}
