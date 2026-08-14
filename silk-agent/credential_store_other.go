//go:build !linux && !darwin && !windows

package main

func platformDeviceCredentialStore(string) deviceCredentialStore { return nil }
