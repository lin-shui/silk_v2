# cc-connect Silk Platform Plugin

A platform plugin for [cc-connect](https://github.com/chenhg5/cc-connect) that connects AI coding agents to Silk chat groups.

## Setup

1. **In Silk**: Create a cc-connect group and copy the generated token.

2. **In cc-connect's `config.toml`**:

```toml
[[projects]]
name = "my-project"

[projects.agent]
type = "claudecode"   # or "cursor", "gemini-cli", "codex", etc.

[projects.agent.options]
work_dir = "/path/to/your/codebase"

[[projects.platforms]]
type = "silk"
[projects.platforms.options]
server = "wss://your-silk-server:15003/ccconnect-bridge"
token  = "paste-your-silk-token-here"
```

3. **Start cc-connect** — it connects to Silk automatically.

## Integration

To integrate this plugin into cc-connect, copy `platform/silk/silk.go` into cc-connect's `platform/silk/` directory and add the import to cc-connect's main package:

```go
import _ "github.com/chenhg5/cc-connect/platform/silk"
```

## Protocol

The plugin communicates with Silk's `/ccconnect-bridge` WebSocket endpoint using a simple JSON protocol. See the plan document for full protocol specification.
