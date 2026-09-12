package com.example.myapplication.stage4

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage9b.DriveImmutableAssetTransfer
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Real generated Drive gateway listing regressions using deterministic HTTP only. */
class DriveGatewayListingPolicyTest {
    @Test
    fun repositoryListing_overBudgetMetadataFailsBeforeAnyDependentMutation() = runBlocking {
        val oversizedName = "x".repeat(2_100_000)
        val transport = ListingTransport { _, _ ->
            """{"files":[{"id":"folder-1","name":"$oversizedName","mimeType":"application/vnd.google-apps.folder","parents":["root"]}]}"""
        }
        val root = Files.createTempDirectory("drive-listing-budget").toFile()
        try {
            val service = driveService(transport)
            val gateway = listingGateway(service, root.toPath())

            val result = gateway.find(SyncScope("account", "root", DocumentId.new()))

            assertTrue(result is RemoteLookup.Failed)
            assertTrue((result as RemoteLookup.Failed).failure is DriveFailure.Pagination)
            assertEquals("the rejected listing performs only its read", 1, transport.requests.size)
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test
    fun repositoryListing_duplicateIdentityAcrossPagesFailsClosed() = runBlocking {
        val transport = ListingTransport { url, _ ->
            if (url.contains("pageToken=page-2")) {
                """{"files":[{"id":"folder-1","name":"plan.pdf","mimeType":"application/vnd.google-apps.folder","parents":["root"]}]}"""
            } else {
                """{"files":[{"id":"folder-1","name":"plan.pdf","mimeType":"application/vnd.google-apps.folder","parents":["root"]}],"nextPageToken":"page-2"}"""
            }
        }
        val root = Files.createTempDirectory("drive-listing-duplicate").toFile()
        try {
            val gateway = listingGateway(driveService(transport), root.toPath())

            val result = gateway.find(SyncScope("account", "root", DocumentId.new()))

            assertTrue(result is RemoteLookup.Failed)
            assertTrue((result as RemoteLookup.Failed).failure is DriveFailure.Pagination)
            assertEquals("the duplicate is found on the second page", 2, transport.requests.size)
        } finally {
            check(root.deleteRecursively())
        }
    }

    // Listing reaches the real generated gateway; only the unrelated durable
    // transfer filesystem uses the repository's existing portable JVM adapter.
    private fun listingGateway(service: Drive, root: java.nio.file.Path) =
        GoogleDriveGateway(service, "account", DriveImmutableAssetTransfer(
            service = service, accountId = "account", stateDirectory = root.resolve("state"),
            stagingDirectory = root.resolve("staging"), operationsFactory = TestPhotoPathOperationsFactory,
            directoryForce = {}))

    private fun driveService(transport: ListingTransport): Drive =
        Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("Drive listing policy regression")
            .setRootUrl("https://www.googleapis.com/")
            .setServicePath("drive/v3/")
            .build()

    private class ListingTransport(
        private val body: (url: String, requestNumber: Int) -> String
    ) : MockHttpTransport() {
        val requests = mutableListOf<String>()

        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    check(method == "GET") { "listing regression must remain read-only" }
                    val requestNumber = synchronized(requests) {
                        requests += url
                        requests.size
                    }
                    return MockLowLevelHttpResponse()
                        .setStatusCode(200)
                        .setContentType("application/json")
                        .setContent(body(url, requestNumber))
                }
            }
    }
}
