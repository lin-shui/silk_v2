package com.silk.android

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowPathUtilsTest {
    @Test
    fun `unix breadcrumb root returns slash`() {
        assertEquals("/", buildBreadcrumbPath(listOf("/", "home", "u"), 0, "/"))
    }

    @Test
    fun `unix breadcrumb mid returns joined path`() {
        assertEquals("/home/u", buildBreadcrumbPath(listOf("/", "home", "u"), 2, "/"))
    }

    @Test
    fun `windows breadcrumb root keeps trailing separator`() {
        assertEquals("C:\\", buildBreadcrumbPath(listOf("C:\\", "Users", "x"), 0, "\\"))
    }

    @Test
    fun `windows breadcrumb mid uses backslash`() {
        assertEquals("C:\\Users\\x", buildBreadcrumbPath(listOf("C:\\", "Users", "x"), 2, "\\"))
    }

    @Test
    fun `breadcrumb empty list returns separator`() {
        assertEquals("/", buildBreadcrumbPath(emptyList(), 0, "/"))
    }

    @Test
    fun `breadcrumb negative index returns separator`() {
        assertEquals("/", buildBreadcrumbPath(listOf("/", "home"), -1, "/"))
    }

    @Test
    fun `joinPath appends separator when parent lacks one`() {
        assertEquals("/home/u/work", joinPath("/home/u", "work", "/"))
    }

    @Test
    fun `joinPath does not duplicate separator`() {
        assertEquals("/home/u/work", joinPath("/home/u/", "work", "/"))
    }

    @Test
    fun `joinPath windows root`() {
        assertEquals("C:\\Users", joinPath("C:\\", "Users", "\\"))
    }

    @Test
    fun `joinPath empty parent returns child`() {
        assertEquals("work", joinPath("", "work", "/"))
    }
}
