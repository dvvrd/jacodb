/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jacodb.impl

import kotlinx.coroutines.*
import org.jacodb.api.*
import org.jacodb.impl.features.classpaths.ClasspathCache
import org.jacodb.impl.features.classpaths.KotlinMetadata
import org.jacodb.impl.features.classpaths.MethodInstructionsFeature
import org.jacodb.impl.fs.*
import org.jacodb.impl.storage.PersistentLocationRegistry
import org.jacodb.impl.vfs.GlobalClassesVfs
import org.jacodb.impl.vfs.RemoveLocationsVisitor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JcDatabaseImpl(
    internal val javaRuntime: JavaRuntime,
    override val persistence: JcDatabasePersistence,
    val featureRegistry: FeaturesRegistry,
    private val settings: JcSettings
) : JcDatabase {

    private val classesVfs = GlobalClassesVfs()
    private val hooks = settings.hooks.map { it(this) }

    internal val locationsRegistry: LocationsRegistry
    private val backgroundJobs = ConcurrentHashMap<Int, Job>()

    private val isClosed = AtomicBoolean()
    private val jobId = AtomicInteger()

    private val backgroundScope = BackgroundScope()

    init {
        featureRegistry.bind(this)
        locationsRegistry = PersistentLocationRegistry(this, featureRegistry)
    }

    override val locations: List<RegisteredLocation>
        get() = locationsRegistry.actualLocations

    suspend fun restore() {
        persistence.setup()
        locationsRegistry.cleanup()
        val runtime = JavaRuntime(settings.jre).allLocations
        locationsRegistry.setup(runtime).new.process(false)
        locationsRegistry.registerIfNeeded(
            settings.predefinedDirOrJars.filter { it.exists() }
                .map { it.asByteCodeLocation(javaRuntime.version, isRuntime = false) }
        ).new.process(true)
    }

    private fun List<JcClasspathFeature>?.appendBuiltInFeatures(): List<JcClasspathFeature> {
        if (this != null && any { it is ClasspathCache }) {
            return this + listOf(KotlinMetadata, MethodInstructionsFeature)
        }
        return listOf(ClasspathCache(settings.cacheSettings), KotlinMetadata, MethodInstructionsFeature) + orEmpty()
    }

    override suspend fun classpath(dirOrJars: List<File>, features: List<JcClasspathFeature>?): JcClasspath {
        assertNotClosed()
        val existedLocations = dirOrJars.filterExisted().map { it.asByteCodeLocation(javaRuntime.version) }
        val processed = locationsRegistry.registerIfNeeded(existedLocations.toList())
            .also { it.new.process(true) }.registered + locationsRegistry.runtimeLocations
        return classpathOf(processed, features)
    }

    override fun classpathOf(locations: List<RegisteredLocation>, features: List<JcClasspathFeature>?): JcClasspath {
        return JcClasspathImpl(
            locationsRegistry.newSnapshot(locations),
            this,
            features.appendBuiltInFeatures(),
            classesVfs
        )
    }

    fun new(cp: JcClasspathImpl): JcClasspath {
        assertNotClosed()
        return JcClasspathImpl(
            locationsRegistry.newSnapshot(cp.registeredLocations),
            cp.db,
            cp.features,
            classesVfs
        )
    }

    override val runtimeVersion: JavaVersion
        get() = javaRuntime.version

    override suspend fun load(dirOrJar: File) = apply {
        assertNotClosed()
        load(listOf(dirOrJar))
    }

    override suspend fun load(dirOrJars: List<File>) = apply {
        assertNotClosed()
        loadLocations(dirOrJars.filterExisted().map { it.asByteCodeLocation(javaRuntime.version) })
    }

    override suspend fun loadLocations(locations: List<JcByteCodeLocation>) = apply {
        assertNotClosed()
        locationsRegistry.registerIfNeeded(locations).new.process(true)
    }

    private suspend fun List<RegisteredLocation>.process(createIndexes: Boolean): List<RegisteredLocation> {
        withContext(Dispatchers.IO) {
            map { location ->
                async {
                    // here something may go wrong
                    location.lazySources.forEach {
                        classesVfs.addClass(it)
                    }
                }
            }
        }.awaitAll()
        val backgroundJobId = jobId.incrementAndGet()
        backgroundJobs[backgroundJobId] = backgroundScope.launch {
            val parentScope = this
            map { location ->
                async {
                    val sources = location.sources
                    parentScope.ifActive { persistence.persist(location, sources) }
                    parentScope.ifActive {
                        classesVfs.visit(
                            RemoveLocationsVisitor(
                                listOf(location),
                                settings.byteCodeSettings.prefixes
                            )
                        )
                    }
                    parentScope.ifActive { featureRegistry.index(location, sources) }
                }
            }.joinAll()
            if (createIndexes) {
                persistence.createIndexes()
            }
            locationsRegistry.afterProcessing(this@process)
            backgroundJobs.remove(backgroundJobId)
        }
        return this
    }

    override suspend fun refresh() {
        awaitBackgroundJobs()
        locationsRegistry.refresh().new.process(true)
        val result = locationsRegistry.cleanup()
        classesVfs.visit(RemoveLocationsVisitor(result.outdated, settings.byteCodeSettings.prefixes))
    }

    override suspend fun rebuildFeatures() {
        awaitBackgroundJobs()
        featureRegistry.broadcast(JcInternalSignal.Drop)

        withContext(Dispatchers.IO) {
            val locations = locationsRegistry.actualLocations
            val parentScope = this
            locations.map {
                async {
                    val addedClasses = persistence.findClassSources(this@JcDatabaseImpl, it)
                    parentScope.ifActive { featureRegistry.index(it, addedClasses) }
                }
            }.joinAll()
        }
    }

    override fun watchFileSystemChanges(): JcDatabase {
        val delay = settings.watchFileSystemDelay?.toLong()
        if (delay != null) { // just paranoid check
            backgroundScope.launch {
                while (true) {
                    delay(delay)
                    refresh()
                }
            }
        }
        return this
    }

    override suspend fun awaitBackgroundJobs() {
        backgroundJobs.values.joinAll()
    }

    override fun isInstalled(feature: JcFeature<*, *>): Boolean {
        return featureRegistry.has(feature)
    }

    suspend fun afterStart() {
        hooks.forEach { it.afterStart() }
    }

    override fun close() {
        isClosed.set(true)
        locationsRegistry.close()
        backgroundJobs.values.forEach {
            it.cancel()
        }
        runBlocking {
            awaitBackgroundJobs()
        }
        backgroundJobs.clear()
        classesVfs.close()
        backgroundScope.cancel()
        persistence.close()
        hooks.forEach { it.afterStop() }
    }

    private fun assertNotClosed() {
        if (isClosed.get()) {
            throw IllegalStateException("Database is already closed")
        }
    }

    private inline fun CoroutineScope.ifActive(action: () -> Unit) {
        if (isActive) {
            action()
        }
    }

}