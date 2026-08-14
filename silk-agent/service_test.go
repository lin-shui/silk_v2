//go:build !windows

package main

import (
	"strings"
	"testing"
)

func TestRenderSystemdUserUnit(t *testing.T) {
	unit := renderSystemdUserUnit("/opt/Silk Agent/silk-agent", "/home/user/Silk Agent/profile")
	if !strings.Contains(unit, "ExecStart=/opt/Silk\\x20Agent/silk-agent run") {
		t.Fatalf("systemd ExecStart was not escaped: %s", unit)
	}
	if !strings.Contains(unit, "Environment=SILK_AGENT_HOME=/home/user/Silk\\x20Agent/profile") {
		t.Fatalf("systemd config directory missing: %s", unit)
	}
}

func TestRenderLaunchAgentPlist(t *testing.T) {
	plist := renderLaunchAgentPlist(`/Applications/Silk & Agent/silk-agent`, `/Users/me/Library/Application Support/silk-agent`)
	if !strings.Contains(plist, "Silk &amp; Agent") {
		t.Fatalf("LaunchAgent XML was not escaped: %s", plist)
	}
	if !strings.Contains(plist, "SILK_AGENT_HOME") {
		t.Fatalf("LaunchAgent environment missing: %s", plist)
	}
}
