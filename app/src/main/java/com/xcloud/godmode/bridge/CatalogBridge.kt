package com.xcloud.godmode.bridge

import com.xcloud.godmode.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge for the CloudCatalogApi using existing CatalogRepository logic.
 */
class CatalogApiBridge(
    private val repo: CatalogRepository,
    private val authManager: XboxFullAuthManager,
    private val gsToken: String,
    private val regionUri: String
) : CloudCatalogApi {

    override suspend fun fetchLaunchableTitles(): CloudCatalogSnapshotDto = withContext(Dispatchers.IO) {
        val titlesJson = authManager.getCloudTitles(gsToken, regionUri)
        val parser = CloudCatalogParser()
        parser.parse(titlesJson, OfferingId("xgpuweb"))
    }
}
