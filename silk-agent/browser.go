package main

import (
	"fmt"
	"os/exec"
	"runtime"
)

func openBrowser(uri string) error {
	var command *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		command = exec.Command("open", uri)
	case "windows":
		command = exec.Command("rundll32", "url.dll,FileProtocolHandler", uri)
	default:
		command = exec.Command("xdg-open", uri)
	}
	if err := command.Start(); err != nil {
		return fmt.Errorf("open browser: %w", err)
	}
	return nil
}
