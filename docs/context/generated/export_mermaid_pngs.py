#!/usr/bin/env python3
"""
从 BUSINESS_FLOW.md 中提取 Mermaid 代码块，导出为 PNG。
使用 mermaid.ink API（无需安装本地依赖）。
"""

import re
import urllib.parse
import urllib.request
import os
import time

MD_PATH = os.path.join(os.path.dirname(__file__), "BUSINESS_FLOW.md")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "diagrams")
os.makedirs(OUTPUT_DIR, exist_ok=True)


def extract_mermaid_blocks(md_content: str):
    """从 Markdown 中提取 Mermaid 代码块，返回 (序号, 标题, 代码) 列表"""
    # 找标题行
    blocks = []
    current_title = None
    current_code = []
    in_block = False
    block_index = 0

    for line in md_content.split("\n"):
        # 检测 Mermaid 块开始
        if line.strip().startswith("```mermaid"):
            in_block = True
            current_code = []
            continue
        if in_block and line.strip().startswith("```"):
            in_block = False
            block_index += 1
            blocks.append((block_index, current_title or f"Diagram {block_index}", "\n".join(current_code)))
            current_title = None
            current_code = []
            continue
        if in_block:
            current_code.append(line)
            continue
        # 标题检测（### 开头的行）
        title_match = re.match(r'^##+\s+(.*)', line)
        if title_match:
            current_title = title_match.group(1).strip()

    return blocks


def render_to_png(mermaid_code: str, output_path: str):
    """通过 Kroki.io API 将 Mermaid 代码渲染为 PNG"""
    import json

    # Kroki.io 支持 POST JSON，不易超长
    url = "https://kroki.io/mermaid/png"
    payload = {
        "diagram_source": mermaid_code,
        "diagram_options": {
            "theme": "default"
        },
        "output_format": "png"
    }
    data = json.dumps(payload).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
    }

    print(f"  📥 Kroki.io 渲染: {output_path}")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            with open(output_path, "wb") as f:
                f.write(response.read())
        print(f"  ✅ 已保存: {output_path}")
        return True
    except Exception as e:
        print(f"  ❌ Kroki 失败: {e}")

    # 方案 B: GoAT 工具（mermaid.ink 的另一种调用方式）
    print(f"  ⚠️  尝试 mermaid.ink URL encode...")
    try:
        # base64 编码（mermaid.ink 要求标准 base64，非 URL safe）
        import base64
        encoded = base64.b64encode(mermaid_code.encode("utf-8")).decode("ascii")
        url2 = f"https://mermaid.ink/img/{encoded}?bgColor=white&scale=2"
        req2 = urllib.request.Request(url2, headers=headers)
        with urllib.request.urlopen(req2, timeout=60) as response:
            with open(output_path, "wb") as f:
                f.write(response.read())
        print(f"  ✅ 已保存: {output_path}")
        return True
    except Exception as e2:
        print(f"  ❌ mermaid.ink base64 也失败: {e2}")
        return False


def main():
    with open(MD_PATH, "r", encoding="utf-8") as f:
        md_content = f.read()

    blocks = extract_mermaid_blocks(md_content)
    print(f"📊 找到 {len(blocks)} 个 Mermaid 图\n")

    for idx, title, code in blocks:
        safe_title = re.sub(r'[^\w\u4e00-\u9fff-]', '_', title)[:40]
        filename = f"{idx:02d}_{safe_title}.png"
        output_path = os.path.join(OUTPUT_DIR, filename)

        print(f"[{idx}/{len(blocks)}] {title}")
        # 先试试直接渲染
        success = render_to_png(code, output_path)
        if not success:
            print(f"  ⚠️  重试中...")
            time.sleep(2)
            render_to_png(code, output_path)
        print()

    print(f"🎉 完成！所有 PNG 已保存到: {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
