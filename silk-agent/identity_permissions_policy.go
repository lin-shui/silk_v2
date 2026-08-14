package main

const (
	windowsLocalSystemSID           = "S-1-5-18"
	windowsBuiltinAdministratorsSID = "S-1-5-32-544"
	windowsCreatorOwnerSID          = "S-1-3-0"
	windowsOwnerRightsSID           = "S-1-3-4"
	windowsTrustedInstallerSID      = "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464"

	windowsGenericAll       uint32 = 0x10000000
	windowsGenericWrite     uint32 = 0x40000000
	windowsDelete           uint32 = 0x00010000
	windowsWriteDAC         uint32 = 0x00040000
	windowsWriteOwner       uint32 = 0x00080000
	windowsFileWriteData    uint32 = 0x00000002
	windowsFileAppendData   uint32 = 0x00000004
	windowsFileWriteEA      uint32 = 0x00000010
	windowsFileDeleteChild  uint32 = 0x00000040
	windowsFileWriteAttrs   uint32 = 0x00000100
	windowsExecutableWrites        = windowsGenericAll | windowsGenericWrite | windowsDelete |
		windowsWriteDAC | windowsWriteOwner | windowsFileWriteData | windowsFileAppendData |
		windowsFileWriteEA | windowsFileDeleteChild | windowsFileWriteAttrs
)

type windowsACLEntry struct {
	SID               string
	AccessPermissions uint32
	AllowsAccess      bool
	InheritOnly       bool
}

func windowsSecretACLIsPrivate(ownerSID string, currentUserSID string, entries []windowsACLEntry) bool {
	if ownerSID == "" || currentUserSID == "" || ownerSID != currentUserSID {
		return false
	}
	allowed := map[string]struct{}{
		currentUserSID:                  {},
		windowsLocalSystemSID:           {},
		windowsBuiltinAdministratorsSID: {},
		windowsCreatorOwnerSID:          {},
		windowsOwnerRightsSID:           {},
	}
	for _, entry := range entries {
		if !entry.AllowsAccess || entry.InheritOnly {
			continue
		}
		if _, exists := allowed[entry.SID]; !exists {
			return false
		}
	}
	return true
}

func windowsExecutableACLIsTrusted(ownerSID string, currentUserSID string, entries []windowsACLEntry) bool {
	trusted := map[string]struct{}{
		currentUserSID:                  {},
		windowsLocalSystemSID:           {},
		windowsBuiltinAdministratorsSID: {},
		windowsTrustedInstallerSID:      {},
		windowsCreatorOwnerSID:          {},
		windowsOwnerRightsSID:           {},
	}
	if ownerSID == "" || currentUserSID == "" {
		return false
	}
	if _, trustedOwner := trusted[ownerSID]; !trustedOwner {
		return false
	}
	for _, entry := range entries {
		if !entry.AllowsAccess || entry.InheritOnly || entry.AccessPermissions&windowsExecutableWrites == 0 {
			continue
		}
		if _, trustedWriter := trusted[entry.SID]; !trustedWriter {
			return false
		}
	}
	return true
}

func windowsDirectoryOwnerMayBeReassigned(ownerSID string, currentUserSID string, elevated bool) bool {
	return elevated &&
		ownerSID == windowsBuiltinAdministratorsSID &&
		currentUserSID != "" &&
		ownerSID != currentUserSID
}
