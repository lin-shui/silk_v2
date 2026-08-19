# Two-layer Agent Permissions

## Goal

Reduce the user-facing permission model to two layers:

1. One Agent runtime mode owned by the Agent owner and managed from Silk `/device`.
2. One Silk access mode on each Workspace Agent binding.

The Agent runtime mode is a per-prompt overlay and never rewrites device CLI config. `NATIVE_DEFAULT` delegates to that config; explicit modes may be more or less permissive than its user default. Identity checks, capability negotiation, binding approval, sandboxing, enterprise policy, OS access, and workspace path validation remain internal safety invariants rather than independent user-configurable permission layers.

## Agent Modes

- `NATIVE_DEFAULT`: do not override the device CLI's approval policy.
- `APPROVAL_REQUIRED`: force the native approval path.
- `READ_ONLY`: remove local write and command grants.
- `AUTOMATIC`: disable native approval prompts only when the Workspace layer also allows autonomous execution; keep Silk sandbox and path/tool gates.

The mode is stored in `agent_instances.runtime_permission_mode`, editable only by the Agent owner, audited, and applied from the next prompt without restarting `silk-agent`.

## Workspace Modes

- `READ_ONLY`: read the bound Workspace; deny file mutation and commands.
- `APPROVAL_REQUIRED`: allow Workspace tools but require the Agent approval path for risky operations.
- `AUTONOMOUS`: Silk adds no approval requirement; the Agent's native policy may still prompt or deny.
- Room bindings are internal `CHAT_ONLY` bindings and never receive local Workspace access.

New Workspaces default to `APPROVAL_REQUIRED`.

## Compatibility

- Keep legacy granular permission JSON as a derived compatibility field while API/UI use `accessMode`.
- Migrate existing Room bindings to `CHAT_ONLY`.
- Migrate existing Workspace bindings with write/command grants to `APPROVAL_REQUIRED`; other Workspace bindings become `READ_ONLY`.
- Accept legacy `permissionMode` input temporarily: `BYPASS` maps to `AUTONOMOUS`; other values map to `APPROVAL_REQUIRED`.
- Require execution-policy v2 support for managed prompts so an old Codex Adapter cannot ignore approval semantics.

## Validation

- Backend Agent auth, binding approval/routing, Workspace, and ACP tests.
- Python bridge policy and executor tests.
- Web model tests and production Kotlin/JS compile.
- Android unit tests and debug Kotlin compile.
- `silk-agent` Go tests and `silkLint`.
