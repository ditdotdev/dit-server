package com.datadatdat

import com.datadatdat.context.RuntimeContext
import com.datadatdat.metadata.MetadataProvider
import com.datadatdat.orchestrator.CommitOrchestrator
import com.datadatdat.orchestrator.OperationOrchestrator
import com.datadatdat.orchestrator.Reaper
import com.datadatdat.orchestrator.RemoteOrchestrator
import com.datadatdat.orchestrator.RepositoryOrchestrator
import com.datadatdat.orchestrator.VolumeOrchestrator
import com.datadatdat.remote.RemoteServer
import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.util.ServiceLoader

class ServiceLocator(
    val context: RuntimeContext,
    inMemory: Boolean = true,
) {
    companion object {
        private val log = LoggerFactory.getLogger(ServiceLocator::class.java)
    }

    private val loader = ServiceLoader.load(RemoteServer::class.java)
    private val remoteProviders: MutableMap<String, RemoteServer>

    init {
        val providers = mutableMapOf<String, RemoteServer>()
        log.info("Starting ServiceLoader discovery for RemoteServer")
        var count = 0
        loader.forEach {
            count++
            if (it != null) {
                val providerName = it.getProvider()
                log.info("Discovered provider '{}' from class {}", providerName, it.javaClass.name)
                providers[providerName] = it
            } else {
                log.warn("Null provider encountered at index {}", count)
            }
        }
        log.info("Total providers discovered: {} ({})", providers.size, providers.keys.joinToString(", "))
        remoteProviders = providers
    }

    val metadata = MetadataProvider(inMemory)
    val commits = CommitOrchestrator(this)
    val repositories = RepositoryOrchestrator(this)
    val operations = OperationOrchestrator(this)
    val volumes = VolumeOrchestrator(this)
    val remotes = RemoteOrchestrator(this)
    val reaper = Reaper(this)

    val gson = GsonBuilder().create()

    // Get a remote provider by name
    fun remoteProvider(type: String): RemoteServer {
        log.debug("Looking up provider '{}' (available: {})", type, remoteProviders.keys.joinToString(", "))
        return remoteProviders[type]
            ?: throw IllegalArgumentException("unknown remote provider '$type' (available: ${remoteProviders.keys.joinToString(", ")})")
    }

    // For testing purposes
    fun setRemoteProvider(
        type: String,
        server: RemoteServer,
    ) {
        remoteProviders[type] = server
    }
}
