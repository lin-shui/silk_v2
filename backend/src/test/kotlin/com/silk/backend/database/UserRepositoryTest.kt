package com.silk.backend.database

import com.silk.backend.TestWorkspace
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserRepositoryTest {

    @Test
    fun `createUser returns null on unique constraint violation`() {
        TestWorkspace().use {
            val first = UserRepository.createUser(
                loginName = "repo-user",
                fullName = "Repo User",
                phoneNumber = "13800000999",
                passwordHash = "hash"
            )
            assertNotNull(first)

            val duplicateLogin = UserRepository.createUser(
                loginName = "repo-user",
                fullName = "Another User",
                phoneNumber = "13800001000",
                passwordHash = "hash"
            )
            assertNull(duplicateLogin)

            val duplicatePhone = UserRepository.createUser(
                loginName = "repo-user-2",
                fullName = "Phone Clash",
                phoneNumber = "13800000999",
                passwordHash = "hash"
            )
            assertNull(duplicatePhone)
        }
    }
}
