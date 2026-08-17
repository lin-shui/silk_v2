package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowPathUtilsTest {
    @Test
    fun unixBreadcrumbUsesExplicitRootSegment() {
        assertEquals("/home/ubuntu", buildBreadcrumbPath(listOf("/", "home", "ubuntu"), 2, "/"))
    }

    @Test
    fun unixBreadcrumbRestoresRootOmittedByLegacyAdapter() {
        assertEquals(
            "/home/ubuntu",
            buildBreadcrumbPath(listOf("home", "ubuntu", ".local", "share"), 1, "/"),
        )
    }

    @Test
    fun windowsBreadcrumbKeepsDriveRoot() {
        assertEquals(
            "C:\\Users\\alice",
            buildBreadcrumbPath(listOf("C:\\", "Users", "alice"), 2, "\\"),
        )
    }
}
