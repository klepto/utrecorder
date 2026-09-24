package dev.klepto.utrecorder.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * Set of instances, each bound to a type.
 *
 * Build one with [dependencies].
 */
interface DependencyRegistry {
    /**
     * Returns every type to instance binding in this registry.
     */
    fun getAll(): Map<KClass<*>, Any>

    /**
     * Returns the instance bound to [type], or `null` if the binding does not exist.
     */
    fun <T : Any> getOrNull(type: KClass<T>): T?

    /**
     * Returns the instance bound to [type].
     *
     * @throws IllegalStateException if the binding for [type] does not exist.
     */
    operator fun <T : Any> get(type: KClass<T>): T
}

/**
 * Returns the instance bound to [T].
 *
 * @throws IllegalStateException if the binding for [T] does not exist.
 */
inline fun <reified T : Any> DependencyRegistry.get(): T = get(T::class)

/**
 * Returns the instance bound to [T], or `null` if the binding does not exist.
 */
inline fun <reified T : Any> DependencyRegistry.getOrNull(): T? = getOrNull(T::class)

/**
 * Dependency registry backed by an immutable [Map].
 *
 * @param dependencyMap the bound instances. Copied on construction.
 */
private class ImmutableDependencyRegistry(
    dependencyMap: Map<KClass<*>, Any>,
) : DependencyRegistry {
    /**
     * Immutable copy of the bound instances.
     */
    private val dependencyMap: Map<KClass<*>, Any> = dependencyMap.toMap()

    override fun getAll() = dependencyMap

    override fun <T : Any> getOrNull(type: KClass<T>) = dependencyMap[type]?.let { type.cast(it) }

    override fun <T : Any> get(type: KClass<T>): T = getOrNull(type) ?: error("No dependency of type $type")
}

/**
 * Builder of a [DependencyRegistry].
 *
 * Use it through [dependencies].
 */
class DependencyBuilder {
    /**
     * The instances bound so far.
     */
    private val dependencyMap = mutableMapOf<KClass<*>, Any>()

    /**
     * Binds [instance] to [type]. If [type] is already bound, replaces the previous binding.
     */
    fun <T : Any> bind(
        type: KClass<T>,
        instance: T,
    ): DependencyBuilder = apply { dependencyMap[type] = instance }

    /**
     * Binds [instance] to its static type [T]. If [T] is already bound, replaces the previous binding.
     */
    inline fun <reified T : Any> bind(instance: T): DependencyBuilder = bind(T::class, instance)

    /**
     * Returns a [DependencyRegistry] holding the current bindings of this builder.
     */
    fun build(): DependencyRegistry = ImmutableDependencyRegistry(dependencyMap)
}

/**
 * Returns a [DependencyRegistry] holding the bindings that [block] makes.
 */
fun dependencies(block: DependencyBuilder.() -> Unit): DependencyRegistry = DependencyBuilder().apply(block).build()

/**
 * Coroutine context element carrying the dependency registry bound to a coroutine context.
 *
 * Bind one through [withDependencies] or [withBinding] rather than constructing it.
 *
 * @property registry the dependency registry bound to the coroutine context.
 */
class DependencyContext(
    val registry: DependencyRegistry,
) : AbstractCoroutineContextElement(DependencyContext) {
    /**
     * Key of [DependencyContext] in a coroutine context.
     */
    companion object Key : CoroutineContext.Key<DependencyContext>
}

/**
 * Returns the dependency registry bound to the current coroutine context, or `null` if the binding does not exist.
 */
suspend fun dependenciesOrNull(): DependencyRegistry? = currentCoroutineContext()[DependencyContext]?.registry

/**
 * Returns the dependency registry bound to the current coroutine context.
 *
 * @throws IllegalStateException if the binding does not exist.
 */
suspend fun dependencies(): DependencyRegistry = dependenciesOrNull() ?: error("No dependency registry bound to current coroutine context.")

/**
 * Returns the instance bound to [T] in the dependency registry of the current coroutine context.
 *
 * @throws IllegalStateException if no dependency registry is bound, or the binding for [T] does not exist.
 */
suspend inline fun <reified T : Any> get(): T = dependencies().get<T>()

/**
 * Returns the instance bound to [T] in the dependency registry of the current coroutine context, or `null` if no
 * dependency registry is bound or the binding for [T] does not exist.
 */
suspend inline fun <reified T : Any> getOrNull(): T? = dependenciesOrNull()?.getOrNull<T>()

/**
 * Runs [block] with [registry] bound to its coroutine context and returns its result.
 *
 * If the current coroutine context already has a dependency registry, [block] sees the bindings of both. A binding
 * of [registry] replaces a binding of the same type in the current one.
 */
suspend fun <T> withDependencies(
    registry: DependencyRegistry,
    block: suspend CoroutineScope.() -> T,
): T {
    val current = dependenciesOrNull()?.getAll().orEmpty()
    val combined = current + registry.getAll()
    return withContext(DependencyContext(ImmutableDependencyRegistry(combined)), block)
}

/**
 * Runs [block] with [instance] bound to its static type [T] and returns its result.
 *
 * Bindings of the current coroutine context stay visible, as in [withDependencies].
 */
suspend inline fun <reified T : Any, R> withBinding(
    instance: T,
    crossinline block: suspend CoroutineScope.() -> R,
): R {
    val registry = dependencies { bind(instance) }
    return withDependencies(registry) { block() }
}
