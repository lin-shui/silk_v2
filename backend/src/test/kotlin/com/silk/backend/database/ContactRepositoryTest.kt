package com.silk.backend.database

import com.silk.backend.TestWorkspace
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContactRepositoryTest {

    @Test
    fun `addContact returns false on duplicate relation`() {
        TestWorkspace().use {
            val user = assertNotNull(
                UserRepository.createUser(
                    loginName = "contact-owner",
                    fullName = "Contact Owner",
                    phoneNumber = "13800002001",
                    passwordHash = "hash"
                )
            )
            val contact = assertNotNull(
                UserRepository.createUser(
                    loginName = "contact-peer",
                    fullName = "Contact Peer",
                    phoneNumber = "13800002002",
                    passwordHash = "hash"
                )
            )

            assertTrue(ContactRepository.addContact(user.id, contact.id))
            assertFalse(ContactRepository.addContact(user.id, contact.id))
        }
    }
}
