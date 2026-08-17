package main

import (
	"errors"
	"fmt"
)

func serviceCommand(arguments []string, configDir string, config hostConfig) error {
	if len(arguments) != 1 {
		return errors.New("service requires one action: install, uninstall, or status")
	}
	switch arguments[0] {
	case "install":
		return installService(configDir, config)
	case "uninstall":
		return uninstallService(configDir)
	case "status":
		status, err := serviceStatus(configDir)
		if err != nil {
			return err
		}
		fmt.Println(status)
		return nil
	default:
		return fmt.Errorf("unknown service action %q", arguments[0])
	}
}
