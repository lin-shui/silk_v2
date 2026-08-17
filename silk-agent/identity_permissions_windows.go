//go:build windows

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var getAceFromACL = windows.NewLazySystemDLL("advapi32.dll").NewProc("GetAce")

const (
	accessAllowedAceType = 0x00
	accessDeniedAceType  = 0x01
)

type windowsACLHeader struct {
	aclRevision byte
	sbz1        byte
	aclSize     uint16
	aceCount    uint16
	sbz2        uint16
}

type windowsACEHeader struct {
	aceType  byte
	aceFlags byte
	aceSize  uint16
}

func protectPrivateFile(path string) error {
	tokenUser, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return fmt.Errorf("resolve current Windows user SID: %w", err)
	}
	if tokenUser.User.Sid == nil || !tokenUser.User.Sid.IsValid() {
		return fmt.Errorf("resolve current Windows user SID: invalid SID")
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
		return fmt.Errorf("transfer protected file ownership to the current Windows user: %w", err)
	}
	return nil
}

func protectPrivateDirectory(path string) error {
	if err := os.MkdirAll(path, 0o700); err != nil {
		return fmt.Errorf("create private directory: %w", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("stat private directory: %w", err)
	}
	if !info.IsDir() {
		return fmt.Errorf("private directory path is not a directory")
	}

	token := windows.GetCurrentProcessToken()
	tokenUser, err := token.GetTokenUser()
	if err != nil {
		return fmt.Errorf("resolve current Windows user SID: %w", err)
	}
	if tokenUser.User.Sid == nil || !tokenUser.User.Sid.IsValid() {
		return fmt.Errorf("resolve current Windows user SID: invalid SID")
	}
	administratorsSID, err := windows.CreateWellKnownSid(windows.WinBuiltinAdministratorsSid)
	if err != nil {
		return fmt.Errorf("resolve Windows Administrators SID: %w", err)
	}
	descriptor, err := windows.GetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION,
	)
	if err != nil {
		return fmt.Errorf("read private directory owner: %w", err)
	}
	owner, _, err := descriptor.Owner()
	if err != nil {
		return fmt.Errorf("read private directory owner SID: %w", err)
	}
	if owner == nil || !owner.IsValid() {
		return fmt.Errorf("read private directory owner SID: invalid SID")
	}
	ownerSID := owner.String()
	currentUserSID := tokenUser.User.Sid.String()
	if ownerSID != currentUserSID {
		if !windowsDirectoryOwnerMayBeReassigned(ownerSID, currentUserSID, token.IsElevated()) {
			return fmt.Errorf("private directory must be owned by the current Windows user")
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
			return fmt.Errorf("transfer elevated private directory ownership to the current Windows user: %w", err)
		}
	}

	systemSID, err := windows.CreateWellKnownSid(windows.WinLocalSystemSid)
	if err != nil {
		return fmt.Errorf("resolve Windows SYSTEM SID: %w", err)
	}
	entries := []windows.EXPLICIT_ACCESS{
		windowsPrivateDirectoryAccessEntry(tokenUser.User.Sid, windows.TRUSTEE_IS_USER),
		windowsPrivateDirectoryAccessEntry(systemSID, windows.TRUSTEE_IS_WELL_KNOWN_GROUP),
		windowsPrivateDirectoryAccessEntry(administratorsSID, windows.TRUSTEE_IS_GROUP),
	}
	dacl, err := windows.ACLFromEntries(entries, nil)
	if err != nil {
		return fmt.Errorf("create private directory DACL: %w", err)
	}
	if err := windows.SetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil,
		nil,
		dacl,
		nil,
	); err != nil {
		return fmt.Errorf("set private directory DACL: %w", err)
	}
	if !windowsPathACLIsPrivate(path) {
		return fmt.Errorf("private directory DACL remains accessible to other Windows users")
	}
	return nil
}

func windowsPrivateDirectoryAccessEntry(sid *windows.SID, trusteeType windows.TRUSTEE_TYPE) windows.EXPLICIT_ACCESS {
	return windows.EXPLICIT_ACCESS{
		AccessPermissions: windows.GENERIC_ALL,
		AccessMode:        windows.SET_ACCESS,
		Inheritance:       windows.SUB_CONTAINERS_AND_OBJECTS_INHERIT,
		Trustee: windows.TRUSTEE{
			TrusteeForm:  windows.TRUSTEE_IS_SID,
			TrusteeType:  trusteeType,
			TrusteeValue: windows.TrusteeValueFromSID(sid),
		},
	}
}

func secretFileProtected(path string, info os.FileInfo) bool {
	if !info.Mode().IsRegular() {
		return false
	}
	return windowsPathACLIsPrivate(path)
}

func windowsPathACLIsPrivate(path string) bool {
	descriptor, err := windows.GetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION,
	)
	if err != nil {
		return false
	}
	owner, _, err := descriptor.Owner()
	if err != nil || owner == nil || !owner.IsValid() {
		return false
	}
	dacl, _, err := descriptor.DACL()
	if err != nil || dacl == nil {
		return false
	}
	tokenUser, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil || tokenUser.User.Sid == nil || !tokenUser.User.Sid.IsValid() {
		return false
	}
	entries, err := readWindowsACLEntries(dacl)
	if err != nil {
		return false
	}
	return windowsSecretACLIsPrivate(owner.String(), tokenUser.User.Sid.String(), entries)
}

func readWindowsACLEntries(dacl *windows.ACL) ([]windowsACLEntry, error) {
	if dacl == nil {
		return nil, syscall.EINVAL
	}
	header := (*windowsACLHeader)(unsafe.Pointer(dacl))
	resultEntries := make([]windowsACLEntry, 0, header.aceCount)
	for index := uint16(0); index < header.aceCount; index++ {
		var ace uintptr
		result, _, _ := getAceFromACL.Call(
			uintptr(unsafe.Pointer(dacl)),
			uintptr(index),
			uintptr(unsafe.Pointer(&ace)),
		)
		if result == 0 || ace == 0 {
			return nil, syscall.EINVAL
		}
		acePointer := unsafe.Pointer(ace)
		aceHeader := (*windowsACEHeader)(acePointer)
		if aceHeader.aceSize < 8 {
			return nil, syscall.EINVAL
		}
		allowsAccess := false
		switch aceHeader.aceType {
		case accessAllowedAceType:
			allowsAccess = true
		case accessDeniedAceType:
		default:
			return nil, syscall.EINVAL
		}
		permissions := *(*uint32)(unsafe.Add(acePointer, 4))
		sid := (*windows.SID)(unsafe.Add(acePointer, 8))
		if !sid.IsValid() {
			return nil, syscall.EINVAL
		}
		resultEntries = append(resultEntries, windowsACLEntry{
			SID:               sid.String(),
			AccessPermissions: permissions,
			AllowsAccess:      allowsAccess,
			InheritOnly:       aceHeader.aceFlags&windows.INHERIT_ONLY_ACE != 0,
		})
	}
	return resultEntries, nil
}

func windowsExecutablePathIsTrusted(path string) bool {
	// Validate the script itself and the directory that can replace it. Walking
	// all the way to the volume root rejects normal Windows layouts where users
	// may create unrelated top-level files even though the bundle directory is
	// not replaceable by them.
	for _, candidate := range []string{path, filepath.Dir(path)} {
		descriptor, err := windows.GetNamedSecurityInfo(
			candidate,
			windows.SE_FILE_OBJECT,
			windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION,
		)
		if err != nil {
			return false
		}
		owner, _, err := descriptor.Owner()
		if err != nil || owner == nil || !owner.IsValid() {
			return false
		}
		dacl, _, err := descriptor.DACL()
		if err != nil || dacl == nil {
			return false
		}
		tokenUser, err := windows.GetCurrentProcessToken().GetTokenUser()
		if err != nil || tokenUser.User.Sid == nil || !tokenUser.User.Sid.IsValid() {
			return false
		}
		entries, err := readWindowsACLEntries(dacl)
		if err != nil || !windowsExecutableACLIsTrusted(owner.String(), tokenUser.User.Sid.String(), entries) {
			return false
		}
	}
	return true
}
