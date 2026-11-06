package com.xcloud.godmode.integration

import com.xcloud.godmode.catalog.*
import com.xcloud.godmode.network.CatalogRepository
import com.xcloud.godmode.network.XboxFullAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bridge for the new CloudCatalogApi using existing CatalogRepository logic.
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
