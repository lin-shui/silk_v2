package com.silk.backend.database

import com.silk.backend.TestWorkspace
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupRepositoryTest {

    @Test
    fun `addUserToGroup returns false on duplicate membership`() {
        TestWorkspace().use {
            val host = assertNotNull(
                UserRepository.createUser(
                    loginName = "group-host",
                    fullName = "Group Host",
                    phoneNumber = "13800003001",
                    passwordHash = "hash"
                )
            )
            val guest = assertNotNull(
                UserRepository.createUser(
                    loginName = "group-guest",
                    fullName = "Group Guest",
                    phoneNumber = "13800003002",
                    passwordHash = "hash"
                )
            )
            val group = assertNotNull(GroupRepository.createGroup(name = "detekt-group", hostId = host.id))

            assertTrue(GroupRepository.addUserToGroup(group.id, guest.id))
            assertFalse(GroupRepository.addUserToGroup(group.id, guest.id))
        }
    }
}
