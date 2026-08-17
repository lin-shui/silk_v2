//go:build windows

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/windows"
)

func TestSecretFileProtectedChecksWindowsDACL(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(path, []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	tokenUser, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	systemSID, err := windows.CreateWellKnownSid(windows.WinLocalSystemSid)
	if err != nil {
		t.Fatal(err)
	}
	administratorsSID, err := windows.CreateWellKnownSid(windows.WinBuiltinAdministratorsSid)
	if err != nil {
		t.Fatal(err)
	}

	privateEntries := []windows.EXPLICIT_ACCESS{
		windowsFullAccessEntry(tokenUser.User.Sid, windows.TRUSTEE_IS_USER),
		windowsFullAccessEntry(systemSID, windows.TRUSTEE_IS_WELL_KNOWN_GROUP),
		windowsFullAccessEntry(administratorsSID, windows.TRUSTEE_IS_GROUP),
	}
	setWindowsTestOwner(t, path)
	setWindowsTestDACL(t, path, privateEntries)
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if !secretFileProtected(path, info) {
		t.Fatal("expected a current-user/SYSTEM/Administrators DACL to be accepted")
	}

	authenticatedUsersSID, err := windows.CreateWellKnownSid(windows.WinAuthenticatedUserSid)
	if err != nil {
		t.Fatal(err)
	}
	broadEntries := append(
		privateEntries,
		windowsFullAccessEntry(authenticatedUsersSID, windows.TRUSTEE_IS_WELL_KNOWN_GROUP),
	)
	setWindowsTestDACL(t, path, broadEntries)
	if secretFileProtected(path, info) {
		t.Fatal("expected Authenticated Users access to be rejected")
	}
}

func TestProtectPrivateDirectoryReplacesInheritedBroadWindowsDACL(t *testing.T) {
	path := filepath.Join(t.TempDir(), "profile")
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatal(err)
	}
	authenticatedUsersSID, err := windows.CreateWellKnownSid(windows.WinAuthenticatedUserSid)
	if err != nil {
		t.Fatal(err)
	}
	setWindowsTestDACL(t, path, []windows.EXPLICIT_ACCESS{
		windowsFullAccessEntry(authenticatedUsersSID, windows.TRUSTEE_IS_WELL_KNOWN_GROUP),
	})

	if err := protectPrivateDirectory(path); err != nil {
		t.Fatal(err)
	}
	if !windowsPathACLIsPrivate(path) {
		t.Fatal("expected the protected directory DACL to be private")
	}

	child := filepath.Join(path, "inherited-secret")
	if err := os.WriteFile(child, []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := protectPrivateFile(child); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(child)
	if err != nil {
		t.Fatal(err)
	}
	if !secretFileProtected(child, info) {
		t.Logf("child ACL: %s", describeWindowsACL(child))
		t.Fatal("expected child files to inherit the private directory DACL")
	}
}

func TestDeviceSignerRejectsBroadWindowsDACL(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "profile")
	signer, err := loadDeviceSignerWithStore(directory, true, nil)
	if err != nil {
		t.Fatal(err)
	}
	authenticatedUsersSID, err := windows.CreateWellKnownSid(windows.WinAuthenticatedUserSid)
	if err != nil {
		t.Fatal(err)
	}
	setWindowsTestDACL(t, signer.path, []windows.EXPLICIT_ACCESS{
		windowsFullAccessEntry(authenticatedUsersSID, windows.TRUSTEE_IS_WELL_KNOWN_GROUP),
	})
	if _, err := loadDeviceSignerWithStore(directory, false, nil); err == nil {
		t.Fatal("expected a broad private-key DACL to be rejected")
	}
}

func TestWindowsExecutableTrustAllowsPublicReadAndRejectsPublicWrite(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "bundle")
	if err := os.Mkdir(directory, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "adapter.py")
	if err := os.WriteFile(path, []byte("print('ok')\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	tokenUser, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	authenticatedUsersSID, err := windows.CreateWellKnownSid(windows.WinAuthenticatedUserSid)
	if err != nil {
		t.Fatal(err)
	}
	readEntries := []windows.EXPLICIT_ACCESS{
		windowsFullAccessEntry(tokenUser.User.Sid, windows.TRUSTEE_IS_USER),
		windowsAccessEntry(authenticatedUsersSID, windows.TRUSTEE_IS_WELL_KNOWN_GROUP, windows.GENERIC_READ),
	}
	setWindowsTestDACL(t, directory, readEntries)
	setWindowsTestDACL(t, path, readEntries)
	if !windowsExecutablePathIsTrusted(path) {
		t.Fatal("expected a publicly readable Adapter script to be trusted")
	}

	writeEntries := append(
		readEntries,
		windowsAccessEntry(authenticatedUsersSID, windows.TRUSTEE_IS_WELL_KNOWN_GROUP, windows.GENERIC_WRITE),
	)
	setWindowsTestDACL(t, path, writeEntries)
	if windowsExecutablePathIsTrusted(path) {
		t.Fatal("expected an Adapter script writable by Authenticated Users to be rejected")
	}

	setWindowsTestDACL(t, path, readEntries)
	setWindowsTestDACL(t, directory, writeEntries)
	if windowsExecutablePathIsTrusted(path) {
		t.Fatal("expected an Adapter script in a replaceable directory to be rejected")
	}
}

func windowsFullAccessEntry(sid *windows.SID, trusteeType windows.TRUSTEE_TYPE) windows.EXPLICIT_ACCESS {
	return windowsAccessEntry(sid, trusteeType, windows.GENERIC_ALL)
}

func windowsAccessEntry(
	sid *windows.SID,
	trusteeType windows.TRUSTEE_TYPE,
	permissions windows.ACCESS_MASK,
) windows.EXPLICIT_ACCESS {
	return windows.EXPLICIT_ACCESS{
		AccessPermissions: permissions,
		AccessMode:        windows.SET_ACCESS,
		Inheritance:       windows.NO_INHERITANCE,
		Trustee: windows.TRUSTEE{
			TrusteeForm:  windows.TRUSTEE_IS_SID,
			TrusteeType:  trusteeType,
			TrusteeValue: windows.TrusteeValueFromSID(sid),
		},
	}
}

func setWindowsTestDACL(t *testing.T, path string, entries []windows.EXPLICIT_ACCESS) {
	t.Helper()
	acl, err := windows.ACLFromEntries(entries, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil,
		nil,
		acl,
		nil,
	); err != nil {
		t.Fatal(err)
	}
}

func setWindowsTestOwner(t *testing.T, path string) {
	t.Helper()
	tokenUser, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION,
		tokenUser.User.Sid,
		nil,
		nil,
		nil,
	); err != nil {
		t.Fatal(err)
	}
}

func describeWindowsACL(path string) string {
	descriptor, err := windows.GetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION,
	)
	if err != nil {
		return fmt.Sprintf("read security descriptor: %v", err)
	}
	owner, _, err := descriptor.Owner()
	if err != nil {
		return fmt.Sprintf("read owner: %v", err)
	}
	dacl, _, err := descriptor.DACL()
	if err != nil {
		return fmt.Sprintf("read DACL: %v", err)
	}
	entries, err := readWindowsACLEntries(dacl)
	if err != nil {
		return fmt.Sprintf("read entries: owner=%v error=%v", owner, err)
	}
	return fmt.Sprintf("owner=%v entries=%+v", owner, entries)
}
