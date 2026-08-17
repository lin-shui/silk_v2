package main

import "testing"

func TestWindowsSecretACLPolicyAllowsOnlyUserAndPrivilegedSystemPrincipals(t *testing.T) {
	const userSID = "S-1-5-21-1-2-3-1001"
	entries := []windowsACLEntry{
		{SID: userSID, AllowsAccess: true},
		{SID: windowsLocalSystemSID, AllowsAccess: true},
		{SID: windowsBuiltinAdministratorsSID, AllowsAccess: true},
	}
	if !windowsSecretACLIsPrivate(userSID, userSID, entries) {
		t.Fatal("expected the current user, SYSTEM, and Administrators ACL to be accepted")
	}
}

func TestWindowsSecretACLPolicyRejectsOtherReaders(t *testing.T) {
	const userSID = "S-1-5-21-1-2-3-1001"
	entries := []windowsACLEntry{
		{SID: userSID, AllowsAccess: true},
		{SID: "S-1-5-11", AllowsAccess: true},
	}
	if windowsSecretACLIsPrivate(userSID, userSID, entries) {
		t.Fatal("expected Authenticated Users access to be rejected")
	}
}

func TestWindowsSecretACLPolicyRejectsDifferentOwnerAndUnknownEntries(t *testing.T) {
	const userSID = "S-1-5-21-1-2-3-1001"
	if windowsSecretACLIsPrivate("S-1-5-21-1-2-3-1002", userSID, nil) {
		t.Fatal("expected a different owner to be rejected")
	}
	if windowsSecretACLIsPrivate(userSID, userSID, []windowsACLEntry{{AllowsAccess: true}}) {
		t.Fatal("expected an allow entry without a SID to be rejected")
	}
}

func TestWindowsSecretACLPolicyIgnoresDenyAndInheritOnlyEntries(t *testing.T) {
	const userSID = "S-1-5-21-1-2-3-1001"
	entries := []windowsACLEntry{
		{SID: userSID, AllowsAccess: true},
		{SID: "S-1-1-0", AllowsAccess: false},
		{SID: "S-1-5-11", AllowsAccess: true, InheritOnly: true},
	}
	if !windowsSecretACLIsPrivate(userSID, userSID, entries) {
		t.Fatal("expected deny and inherit-only entries not to grant file access")
	}
}

func TestWindowsExecutableACLPolicyAllowsPublicReadButRejectsPublicWrite(t *testing.T) {
	const userSID = "S-1-5-21-1-2-3-1001"
	readable := []windowsACLEntry{
		{SID: userSID, AccessPermissions: windowsGenericAll, AllowsAccess: true},
		{SID: "S-1-5-11", AccessPermissions: 0x80000000, AllowsAccess: true},
	}
	if !windowsExecutableACLIsTrusted(userSID, userSID, readable) {
		t.Fatal("expected a publicly readable but non-writable executable to be accepted")
	}
	writable := append(readable, windowsACLEntry{
		SID: "S-1-5-11", AccessPermissions: windowsGenericWrite, AllowsAccess: true,
	})
	if windowsExecutableACLIsTrusted(userSID, userSID, writable) {
		t.Fatal("expected an executable writable by Authenticated Users to be rejected")
	}
}

func TestWindowsExecutableACLPolicyRequiresTrustedOwner(t *testing.T) {
	const userSID = "S-1-5-21-1-2-3-1001"
	if windowsExecutableACLIsTrusted("S-1-5-21-9-9-9-1002", userSID, nil) {
		t.Fatal("expected an executable owned by another ordinary user to be rejected")
	}
	if !windowsExecutableACLIsTrusted(windowsTrustedInstallerSID, userSID, nil) {
		t.Fatal("expected a TrustedInstaller-owned executable to be accepted")
	}
}

func TestWindowsDirectoryOwnerReassignmentPolicy(t *testing.T) {
	const currentUserSID = "S-1-5-21-1000"
	tests := []struct {
		name     string
		ownerSID string
		elevated bool
		expected bool
	}{
		{name: "elevated administrators owner", ownerSID: windowsBuiltinAdministratorsSID, elevated: true, expected: true},
		{name: "non-elevated administrators owner", ownerSID: windowsBuiltinAdministratorsSID, elevated: false, expected: false},
		{name: "another user owner", ownerSID: "S-1-5-21-2000", elevated: true, expected: false},
		{name: "current user already owns directory", ownerSID: currentUserSID, elevated: true, expected: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			actual := windowsDirectoryOwnerMayBeReassigned(test.ownerSID, currentUserSID, test.elevated)
			if actual != test.expected {
				t.Fatalf("expected %v, got %v", test.expected, actual)
			}
		})
	}
}
