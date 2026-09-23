package dev.klepto.utrecorder.common.inject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.cast

interface Dependencies {
    fun getAll(): Map<KClass<*>, Any>

    fun <T : Any> getOrNull(type: KClass<T>): T?

    fun <T : Any> get(type: KClass<T>): T
}

private class ImmutableDependencies(
    private val dependencyMap: Map<KClass<*>, Any>,
) : Dependencies {
    override fun getAll() = dependencyMap

    override fun <T : Any> getOrNull(type: KClass<T>) = dependencyMap[type]?.let { type.cast(it) }

    override fun <T : Any> get(type: KClass<T>): T {
        val dependency = dependencyMap[type]
        checkNotNull(dependency) { "No dependency of type $type" }
        check(type.isInstance(dependency)) { "Dependency of type $type is not an instance of $type" }
        return type.cast(dependency)
    }
}

class DependencyBuilder {
    private val dependencyMap = mutableMapOf<KClass<*>, Any>()

    fun <T : Any> bind(
        type: KClass<T>,
        instance: T,
    ): DependencyBuilder = apply { dependencyMap[type] = instance }

    inline fun <reified T : Any> bind(instance: T): DependencyBuilder = apply { bind(T::class, instance) }

    fun build(): Dependencies = ImmutableDependencies(dependencyMap)
}

suspend fun dependenciesOrNull(): Dependencies? = currentCoroutineContext()[DependencyContext]?.dependencies

suspend fun dependencies(): Dependencies = dependenciesOrNull() ?: error("No dependencies bound to current coroutine context.")

fun dependencies(block: DependencyBuilder.() -> Unit): Dependencies = DependencyBuilder().apply(block).build()

suspend fun <T> withDependencies(
    dependencies: Dependencies,
    block: suspend CoroutineScope.() -> T,
): T {
    val current = dependenciesOrNull()?.getAll() ?: emptyMap()
    val combined = current + dependencies.getAll()
    return withContext(DependencyContext(ImmutableDependencies(combined)), block)
}

suspend inline fun <reified T : Any, R> withBinding(
    instance: T,
    crossinline block: suspend CoroutineScope.() -> R,
): R {
    val dependencies = dependencies { bind(T::class, instance) }
    return withDependencies(dependencies) { block() }
}

class DependencyContext(
    val dependencies: Dependencies,
) : AbstractCoroutineContextElement(DependencyContext) {
    companion object Key : CoroutineContext.Key<DependencyContext>
}

suspend inline fun <reified T : Any> get(): T = dependencies().get<T>()

suspend inline fun <reified T : Any> getOrNull(): T? = dependenciesOrNull()?.getOrNull<T>()

inline fun <reified T : Any> Dependencies.get(): T = get(T::class)

inline fun <reified T : Any> Dependencies.getOrNull(): T? = getOrNull(T::class)
