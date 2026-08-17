package main

type deviceCredentialStore interface {
	name() string
	available() bool
	load() ([]byte, bool, error)
	store([]byte) error
	remove() error
}
