from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from bridge_common.execution_policy import ExecutionPolicy, resolve_prompt_working_directory
from codex_bridge.codex_executor import CodexExecutor


class ExecutionPolicyTest(unittest.TestCase):
    def test_missing_envelope_is_legacy_but_malformed_envelope_denies_everything(self) -> None:
        self.assertIsNone(ExecutionPolicy.from_acp_prompt({"sessionId": "legacy"}))
        self.assertEqual(
            ExecutionPolicy(),
            ExecutionPolicy.from_acp_prompt(
                {"sessionId": "managed"},
                managed_connection=True,
            ),
        )
        malformed = ExecutionPolicy.from_acp_prompt({"_silk": {"protocolVersion": 99}})
        self.assertEqual(ExecutionPolicy(), malformed)

    def test_parses_only_literal_boolean_grants(self) -> None:
        policy = ExecutionPolicy.from_acp_prompt({
            "_silk": {
                "protocolVersion": 1,
                "executionPolicy": {
                    "readFile": True,
                    "writeFile": "true",
                    "runCommand": 1,
                },
            },
        })
        self.assertEqual(ExecutionPolicy(read_file=True), policy)

    def test_claude_policy_keeps_native_config_and_denies_ungranted_local_tools(self) -> None:
        policy = ExecutionPolicy(read_file=True)
        args = policy.claude_cli_args()
        denied = args[args.index("--disallowedTools") + 1].split(",")
        settings = json.loads(args[args.index("--settings") + 1])
        self.assertNotIn("--setting-sources", args)
        self.assertNotIn("--strict-mcp-config", args)
        self.assertNotIn("--disable-slash-commands", args)
        self.assertNotIn("--tools", args)
        self.assertEqual("manual", args[args.index("--permission-mode") + 1])
        self.assertIn("Write", denied)
        self.assertIn("Bash", denied)
        self.assertNotIn("Read", denied)
        self.assertNotIn("sandbox", settings)

    def test_claude_bash_requires_complete_grant_and_hard_sandbox(self) -> None:
        policy = ExecutionPolicy(read_file=True, write_file=True, run_command=True)
        args = policy.claude_cli_args()
        settings = json.loads(args[args.index("--settings") + 1])
        self.assertNotIn("--disallowedTools", args)
        self.assertTrue(settings["sandbox"]["enabled"])
        self.assertTrue(settings["sandbox"]["failIfUnavailable"])
        self.assertFalse(settings["sandbox"]["allowUnsandboxedCommands"])

    def test_file_tools_are_limited_to_workspace(self) -> None:
        policy = ExecutionPolicy(read_file=True, write_file=True)
        self.assertIsNone(policy.denied_tool_reason("Edit", {"file_path": "src/a.kt"}, "/work/project"))
        self.assertIsNotNone(policy.denied_tool_reason("Edit", {"file_path": "../secret"}, "/work/project"))
        self.assertIsNotNone(policy.denied_tool_reason("Read", {"file_path": "/etc/passwd"}, "/work/project"))

    def test_codex_policy_separates_sandbox_and_shell(self) -> None:
        self.assertEqual("read-only", ExecutionPolicy(read_file=True).codex_sandbox)
        self.assertFalse(ExecutionPolicy(read_file=True).can_run_codex_shell)
        writable = ExecutionPolicy(read_file=True, write_file=True)
        self.assertEqual("workspace-write", writable.codex_sandbox)
        self.assertFalse(writable.can_run_codex_shell)
        command = ExecutionPolicy(read_file=True, run_command=True)
        self.assertEqual("read-only", command.codex_sandbox)
        self.assertTrue(command.can_run_codex_shell)

    def test_codex_executor_applies_managed_policy_without_dangerous_bypass(self) -> None:
        command = CodexExecutor(auto_approve=True)._build_cmd(
            cwd="/work/project",
            resume_thread_id=None,
            execution_policy=ExecutionPolicy(read_file=True),
        )
        self.assertEqual("read-only", command[command.index("--sandbox") + 1])
        self.assertIn("shell_tool", command)
        self.assertIn("unified_exec", command)
        self.assertNotIn("--ignore-user-config", command)
        self.assertNotIn("--ignore-rules", command)
        self.assertNotIn("--dangerously-bypass-approvals-and-sandbox", command)

    def test_message_only_prompt_falls_back_to_agent_local_default(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            local_default = Path(root) / "agent-home"
            local_default.mkdir()
            resolved, used_default = resolve_prompt_working_directory(
                str(Path(root) / "missing-other-device-path"),
                str(local_default),
                ExecutionPolicy(),
            )
            self.assertEqual(str(local_default.resolve()), resolved)
            self.assertTrue(used_default)

    def test_workspace_prompt_rejects_missing_bound_directory(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            local_default = Path(root) / "agent-home"
            local_default.mkdir()
            with self.assertRaisesRegex(ValueError, "Bound working directory"):
                resolve_prompt_working_directory(
                    str(Path(root) / "missing-other-device-path"),
                    str(local_default),
                    ExecutionPolicy(read_file=True),
                )

    def test_existing_bound_directory_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root) / "workspace"
            workspace.mkdir()
            local_default = Path(root) / "agent-home"
            local_default.mkdir()
            resolved, used_default = resolve_prompt_working_directory(
                str(workspace),
                str(local_default),
                ExecutionPolicy(read_file=True, write_file=True, run_command=True),
            )
            self.assertEqual(str(workspace.resolve()), resolved)
            self.assertFalse(used_default)


if __name__ == "__main__":
    unittest.main()
