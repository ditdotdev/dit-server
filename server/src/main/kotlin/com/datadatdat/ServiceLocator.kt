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
import java.util.ServiceLoader

class ServiceLocator(
    val context: RuntimeContext,
    inMemory: Boolean = true,
) {
    private val loader = ServiceLoader.load(RemoteServer::class.java)
    private val remoteProviders: MutableMap<String, RemoteServer>

    init {
        val providers = mutableMapOf<String, RemoteServer>()
        loader.forEach {
            if (it != null) {
                providers[it.getProvider()] = it
            }
        }
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
    fun remoteProvider(type: String): RemoteServer =
        remoteProviders[type]
            ?: throw IllegalArgumentException("unknown remote provider '$type'")

    // For testing purposes
    fun setRemoteProvider(
        type: String,
        server: RemoteServer,
    ) {
        remoteProviders[type] = server
    }
}
