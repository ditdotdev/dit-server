package dev.dit

import com.google.gson.GsonBuilder
import dev.dit.context.RuntimeContext
import dev.dit.metadata.MetadataProvider
import dev.dit.orchestrator.CommitOrchestrator
import dev.dit.orchestrator.OperationOrchestrator
import dev.dit.orchestrator.Reaper
import dev.dit.orchestrator.RemoteOrchestrator
import dev.dit.orchestrator.RepositoryOrchestrator
import dev.dit.orchestrator.VolumeOrchestrator
import dev.dit.remote.RemoteServer
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
