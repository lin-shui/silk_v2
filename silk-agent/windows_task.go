package main

import (
	"errors"
	"strings"
)

func renderWindowsTaskAction(executable string, configDir string) (string, error) {
	if executable == "" || configDir == "" {
		return "", errors.New("Windows service executable and configuration directory are required")
	}
	if strings.ContainsAny(executable, "\x00\r\n") || strings.ContainsAny(configDir, "\x00\r\n") {
		return "", errors.New("Windows service paths cannot contain control characters")
	}
	return quoteWindowsCommandLineArgument(executable) +
		" run --config-dir " + quoteWindowsCommandLineArgument(configDir), nil
}

// quoteWindowsCommandLineArgument follows CommandLineToArgvW escaping rules.
// In particular, trailing backslashes are doubled before the closing quote.
func quoteWindowsCommandLineArgument(argument string) string {
	if argument != "" && !strings.ContainsAny(argument, " \t\"") {
		return argument
	}
	var quoted strings.Builder
	quoted.WriteByte('"')
	backslashes := 0
	for _, character := range argument {
		switch character {
		case '\\':
			backslashes++
		case '"':
			quoted.WriteString(strings.Repeat("\\", backslashes*2+1))
			quoted.WriteByte('"')
			backslashes = 0
		default:
			quoted.WriteString(strings.Repeat("\\", backslashes))
			quoted.WriteRune(character)
			backslashes = 0
		}
	}
	quoted.WriteString(strings.Repeat("\\", backslashes*2))
	quoted.WriteByte('"')
	return quoted.String()
}
