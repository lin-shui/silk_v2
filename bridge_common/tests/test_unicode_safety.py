from bridge_common.host_ipc import HostIPC
from bridge_common.unicode_safety import sanitize_json_value, sanitize_text


def test_sanitize_text_preserves_normal_unicode_and_decodes_pairs():
    assert sanitize_text("中文 😀") == "中文 😀"
    assert sanitize_text("\\ud83d\\ude00") == "\\ud83d\\ude00"
    assert sanitize_text("\ud83d\ude00") == "😀"


def test_sanitize_text_replaces_lone_surrogates():
    assert sanitize_text("before\udca4after") == "before�after"
    assert sanitize_text("\ud83d") == "�"


def test_sanitize_json_value_recurses():
    value = {"\udca4": ["ok", "\udca4"], "nested": {"x": "\ud83d\ude00"}}
    assert sanitize_json_value(value) == {"�": ["ok", "�"], "nested": {"x": "😀"}}


def test_host_ipc_decode_sanitizes_escaped_surrogate():
    message = HostIPC._decode('{"jsonrpc":"2.0","params":{"text":"\\udca4"}}')
    assert message["params"]["text"] == "�"
