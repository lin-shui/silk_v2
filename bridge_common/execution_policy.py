from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any


SILK_PROMPT_POLICY_VERSION = 2

CHAT_ONLY = "CHAT_ONLY"
READ_ONLY = "READ_ONLY"
APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
AUTONOMOUS = "AUTONOMOUS"
_ACCESS_MODES = {CHAT_ONLY, READ_ONLY, APPROVAL_REQUIRED, AUTONOMOUS}

NATIVE_DEFAULT = "NATIVE_DEFAULT"
AGENT_APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
AGENT_READ_ONLY = "READ_ONLY"
AUTOMATIC = "AUTOMATIC"
_AGENT_PERMISSION_MODES = {
    NATIVE_DEFAULT,
    AGENT_APPROVAL_REQUIRED,
    AGENT_READ_ONLY,
    AUTOMATIC,
}

_CLAUDE_READ_TOOLS = ("Glob", "Grep", "Read")
_CLAUDE_WRITE_TOOLS = ("Edit", "NotebookEdit", "Write")


@dataclass(frozen=True)
class ExecutionPolicy:
    """Server-derived core local file/command ceiling for one managed prompt."""

    access_mode: str = CHAT_ONLY
    agent_permission_mode: str = NATIVE_DEFAULT
    read_file: bool = False
    write_file: bool = False
    run_command: bool = False

    @classmethod
    def from_acp_prompt(
        cls,
        params: Any,
        *,
        managed_connection: bool = False,
    ) -> ExecutionPolicy | None:
        """Return None only when a legacy connection has no Silk envelope."""
        if not isinstance(params, dict) or "_silk" not in params:
            return cls() if managed_connection else None
        silk = params.get("_silk")
        if not isinstance(silk, dict) or silk.get("protocolVersion") not in {1, SILK_PROMPT_POLICY_VERSION}:
            return cls()
        raw = silk.get("executionPolicy")
        if not isinstance(raw, dict):
            return cls()
        read_file = raw.get("readFile") is True
        write_file = raw.get("writeFile") is True
        run_command = raw.get("runCommand") is True
        raw_mode = raw.get("accessMode")
        raw_agent_mode = raw.get("agentPermissionMode")
        if silk.get("protocolVersion") == 1:
            access_mode = (
                APPROVAL_REQUIRED if write_file or run_command
                else READ_ONLY if read_file
                else CHAT_ONLY
            )
        else:
            access_mode = raw_mode if raw_mode in _ACCESS_MODES else CHAT_ONLY
        return cls(
            access_mode=access_mode,
            agent_permission_mode=(
                raw_agent_mode if raw_agent_mode in _AGENT_PERMISSION_MODES else NATIVE_DEFAULT
            ),
            read_file=read_file,
            write_file=write_file,
            run_command=run_command,
        )

    @property
    def can_run_claude_bash(self) -> bool:
        # Claude's Bash sandbox does not independently hide readable files, so
        # require the complete file/command grant before exposing the tool.
        return self.read_file and self.write_file and self.run_command

    @property
    def can_run_codex_shell(self) -> bool:
        return self.read_file and self.run_command

    @property
    def has_local_workspace_access(self) -> bool:
        """Whether this prompt is allowed to depend on a bound local cwd."""
        return self.read_file or self.write_file or self.run_command

    @property
    def codex_sandbox(self) -> str:
        return "workspace-write" if self.read_file and self.write_file else "read-only"

    @property
    def requires_approval(self) -> bool:
        return self.access_mode == APPROVAL_REQUIRED

    @property
    def automatic_execution(self) -> bool:
        """Whether Silk should override native approvals for this prompt.

        Workspace approval/read-only modes remain stricter and therefore never
        enable automatic execution, even if the Agent owner selected it.
        """
        return self.access_mode == AUTONOMOUS and self.agent_permission_mode == AUTOMATIC

    def claude_cli_args(self) -> list[str]:
        denied: list[str] = []
        if not self.read_file:
            denied.extend(_CLAUDE_READ_TOOLS)
        if not self.write_file:
            denied.extend(_CLAUDE_WRITE_TOOLS)
        if not self.can_run_claude_bash:
            denied.append("Bash")

        settings: dict[str, Any] = {}
        if denied:
            settings["permissions"] = {"deny": sorted(denied)}
        if self.can_run_claude_bash:
            settings["sandbox"] = {
                "enabled": self.can_run_claude_bash,
                "failIfUnavailable": self.can_run_claude_bash,
                "autoAllowBashIfSandboxed": False,
                "allowUnsandboxedCommands": False,
            }
        args = [
            # Load Claude exactly as the device user normally runs it. The
            # additional settings only cap the built-in local file/command
            # tools; native user/project settings, hooks, MCP, plugins, rules,
            # authentication, provider and model selection remain available.
            "--settings", json.dumps(settings, separators=(",", ":")),
        ]
        if self.requires_approval:
            args.extend(("--permission-mode", "manual"))
        elif self.automatic_execution:
            args.extend(("--permission-mode", "bypassPermissions"))
        if denied:
            args.extend(("--disallowedTools", ",".join(sorted(denied))))
        return args

    def denied_tool_reason(
        self,
        tool_name: str,
        tool_input: dict[str, Any],
        working_dir: str,
    ) -> str | None:
        if tool_name in _CLAUDE_READ_TOOLS:
            if not self.read_file:
                return "Silk binding does not grant file read access."
            return _outside_workspace_reason(tool_name, tool_input, working_dir)
        if tool_name in _CLAUDE_WRITE_TOOLS:
            if not self.write_file:
                return "Silk binding does not grant file write access."
            return _outside_workspace_reason(tool_name, tool_input, working_dir)
        if tool_name == "Bash":
            if not self.can_run_claude_bash:
                return "Silk binding does not grant the complete file and command permission set."
            return None
        # Device-native tools (MCP, plugins, skills, agents, web tools, and
        # future CLI built-ins) keep their normal local configuration. Silk's
        # current lightweight ceiling applies only to the core local
        # file/command tools above.
        return None


def resolve_prompt_working_directory(
    candidate: str,
    default_cwd: str,
    execution_policy: ExecutionPolicy | None,
) -> tuple[str, bool]:
    """Resolve an Adapter cwd without silently changing a workspace binding.

    Message-only managed prompts may safely use the Agent device's local
    default when an old/server/other-OS path is unavailable. A prompt with any
    local workspace capability must instead fail closed so it cannot execute
    against a different directory than the one the user selected.

    Returns ``(resolved_path, used_default)``.
    """
    resolved = os.path.realpath(candidate) if candidate else ""
    if resolved and os.path.isdir(resolved):
        return resolved, False

    fallback = os.path.realpath(default_cwd)
    if execution_policy is not None and not execution_policy.has_local_workspace_access:
        if not os.path.isdir(fallback):
            raise ValueError("Agent default working directory is unavailable")
        return fallback, True

    raise ValueError("Bound working directory does not exist on this Agent device")


def _outside_workspace_reason(
    tool_name: str,
    tool_input: dict[str, Any],
    working_dir: str,
) -> str | None:
    fields = {
        "Read": ("file_path",),
        "Write": ("file_path",),
        "Edit": ("file_path",),
        "NotebookEdit": ("notebook_path",),
        "Glob": ("path",),
        "Grep": ("path",),
    }.get(tool_name, ())
    for field in fields:
        candidate = tool_input.get(field)
        if isinstance(candidate, str) and candidate.strip():
            if not _is_within(candidate, working_dir):
                return "Silk binding limits file tools to the bound workspace."
    return None


def _is_within(candidate: str, working_dir: str) -> bool:
    root = os.path.realpath(working_dir)
    expanded = os.path.expanduser(candidate)
    path = os.path.realpath(expanded if os.path.isabs(expanded) else os.path.join(root, expanded))
    try:
        return os.path.commonpath((root, path)) == root
    except ValueError:
        return False
