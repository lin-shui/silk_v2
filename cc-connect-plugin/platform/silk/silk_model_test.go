package silk

import "testing"

func TestParseModelResponse_HyphenatedNames(t *testing.T) {
	text := `当前模型: claude-opus-4.6

可用模型:
> 1. claude-opus-4.6 — Opus 4.6
  2. claude-sonnet-4.6 — Sonnet 4.6
  3. gpt-5.3-codex — Codex`

	current, models := parseModelResponse(text)
	if current != "claude-opus-4.6" {
		t.Fatalf("current = %q, want claude-opus-4.6", current)
	}
	if len(models) != 3 {
		t.Fatalf("models len = %d, want 3", len(models))
	}
	if models[0]["name"] != "claude-opus-4.6" {
		t.Fatalf("models[0].name = %q, want claude-opus-4.6", models[0]["name"])
	}
	if models[1]["name"] != "claude-sonnet-4.6" {
		t.Fatalf("models[1].name = %q, want claude-sonnet-4.6", models[1]["name"])
	}
}

func TestParseModelResponse_AliasLine(t *testing.T) {
	text := `Current model: gpt-5.3-codex

> 1. codex - gpt-5.3-codex
  2. opus - claude-opus-4.6`

	current, models := parseModelResponse(text)
	if current != "gpt-5.3-codex" {
		t.Fatalf("current = %q, want gpt-5.3-codex", current)
	}
	if len(models) != 2 {
		t.Fatalf("models len = %d, want 2", len(models))
	}
	if models[0]["name"] != "gpt-5.3-codex" || models[0]["desc"] != "codex" {
		t.Fatalf("models[0] = %#v, want name=gpt-5.3-codex desc=codex", models[0])
	}
	if models[1]["name"] != "claude-opus-4.6" {
		t.Fatalf("models[1].name = %q, want claude-opus-4.6", models[1]["name"])
	}
}
