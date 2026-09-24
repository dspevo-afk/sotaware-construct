package com.example.myapplication.projects

import com.example.myapplication.stage9.DriveAuthorizationRequestResult
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GoogleDriveProjectsTest {
    @Test fun folderNameSearchIsNotRestrictedToMyDriveRootAndEscapesQueryText() = runBlocking {
        val transport = FixtureTransport { """{"files":[${folder("nested-folder")}]}""" }
        val result = gateway(transport).searchFolders("Bid's Folder")
        assertEquals("nested-folder", result.single().id)
        val query = java.net.URLDecoder.decode(transport.requests.single(), "UTF-8")
        assertTrue(query.contains("name contains 'Bid\\'s Folder'"))
        assertFalse(query.contains("in parents"))
        assertTrue(query.contains(DRIVE_FOLDER_MIME))
    }
    @Test fun folderListingUsesEveryPageAndOnlyReadRequests() = runBlocking {
        val transport = FixtureTransport { page -> if (page == 1) """{"nextPageToken":"more","files":[${folder("a")}]}"""
            else """{"files":[${folder("b")}]}""" }
        val gateway = gateway(transport)
        assertEquals(listOf("a", "b"), gateway.children("project").map { it.id })
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[1].contains("pageToken=more"))
        assertTrue(transport.requests.all { it.contains("supportsAllDrives=true") && it.contains("includeItemsFromAllDrives=true") })
    }

    @Test fun duplicateEntriesAndRepeatedPaginationCannotBecomePartialSuccess() = runBlocking {
        val duplicate = FixtureTransport { """{"nextPageToken":"more","files":[${folder("a")}]}""" }
        try { gateway(duplicate).children("project"); fail("Duplicate listing accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(2, duplicate.requests.size)
        val repeated = FixtureTransport { page -> """{"nextPageToken":"more","files":[${folder("folder$page")}]}""" }
        try { gateway(repeated).children("project"); fail("Repeated page accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(2, repeated.requests.size)
    }

    @Test fun incompleteSearchAndUnavailableFolderAreFailures() = runBlocking {
        try { gateway(FixtureTransport { """{"incompleteSearch":true,"files":[]}""" }).children("project"); fail("Incomplete search accepted") }
        catch (_: IllegalArgumentException) { }
        val unavailable = FixtureTransport(status = 404) { "{}" }
        try { gateway(unavailable).children("project"); fail("Unavailable folder accepted") } catch (_: IOException) { }
    }

    @Test fun expiredSessionCannotStartAnotherRequestOrReenterDownload() = runBlocking {
        val transport = FixtureTransport { "{}" }
        val gateway = gateway(transport)
        gateway.close()
        try { gateway.children("project"); fail("Closed session reused") } catch (_: IOException) { }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun backupOnlyGrantCannotAuthorizeProjectReads() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleDriveProjects("account", DriveAuthorizationRequestResult.Granted("synthetic-token", setOf("https://www.googleapis.com/auth/drive.file")))
        }
    }

    private fun gateway(transport: FixtureTransport) = GoogleDriveProjects("synthetic-account",
        DriveAuthorizationRequestResult.Granted("synthetic-token", setOf(DRIVE_PROJECT_READ_SCOPE)), transport)
    private fun folder(id: String) = """{"id":"$id","name":"Same name","mimeType":"application/vnd.google-apps.folder"}"""
    private class FixtureTransport(private val status: Int = 200, private val body: (Int) -> String) : MockHttpTransport() {
        val requests = mutableListOf<String>()
        override fun buildRequest(method: String, url: String): LowLevelHttpRequest = object : MockLowLevelHttpRequest(url) {
            override fun execute(): LowLevelHttpResponse {
                check(method == "GET")
                requests += url
                return MockLowLevelHttpResponse().setStatusCode(status).setContentType("application/json").setContent(body(requests.size))
            }
        }
    }
}
