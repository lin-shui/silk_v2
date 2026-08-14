package main

import (
	"testing"
)

func TestRenderWindowsTaskActionPersistsConfigDirectory(t *testing.T) {
	action, err := renderWindowsTaskAction(
		`C:\Program Files\Silk Agent\silk-agent.exe`,
		`D:\Profiles\Alice Smith\Silk Agent\`,
	)
	if err != nil {
		t.Fatal(err)
	}
	expected := `"C:\Program Files\Silk Agent\silk-agent.exe" run --config-dir "D:\Profiles\Alice Smith\Silk Agent\\"`
	if action != expected {
		t.Fatalf("unexpected Scheduled Task action:\nwant %s\n got %s", expected, action)
	}
}

func TestRenderWindowsTaskActionRejectsControlCharacters(t *testing.T) {
	if _, err := renderWindowsTaskAction("silk-agent.exe", "C:\\Silk\nInjected"); err == nil {
		t.Fatal("expected a config directory containing a newline to be rejected")
	}
}
