package main

import "encoding/json"

const adapterProtocolVersion = "2"

type adapterRPCMessage struct {
	JSONRPC string           `json:"jsonrpc"`
	ID      string           `json:"id,omitempty"`
	Method  string           `json:"method,omitempty"`
	Params  json.RawMessage  `json:"params,omitempty"`
	Result  json.RawMessage  `json:"result,omitempty"`
	Error   *adapterRPCError `json:"error,omitempty"`
}

type adapterRPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type adapterInitializeParams struct {
	ProtocolVersion   string   `json:"protocolVersion"`
	Nonce             string   `json:"nonce"`
	AgentInstanceID   string   `json:"agentInstanceId"`
	AgentType         string   `json:"agentType"`
	HostVersion       string   `json:"hostVersion"`
	AgentCapabilities []string `json:"agentCapabilities"`
}

type adapterInitializeResult struct {
	ProtocolVersion string   `json:"protocolVersion"`
	Nonce           string   `json:"nonce"`
	AgentInstanceID string   `json:"agentInstanceId"`
	AdapterVersion  string   `json:"adapterVersion"`
	Capabilities    []string `json:"capabilities,omitempty"`
}

type adapterHealthResult struct {
	Status string `json:"status"`
}

type adapterACPParams struct {
	Message json.RawMessage `json:"message"`
}
