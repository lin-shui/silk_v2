#!/usr/bin/env python3
"""
从 BUSINESS_FLOW.md 提取每个 Mermaid 图到独立的 .mmd 文件。
"""

import re
import os

MD_PATH = os.path.join(os.path.dirname(__file__), "BUSINESS_FLOW.md")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "mmd")
os.makedirs(OUTPUT_DIR, exist_ok=True)


def extract_mermaid_blocks(md_content: str):
    blocks = []
    current_title = None
    current_code = []
    in_block = False
    block_index = 0

    for line in md_content.split("\n"):
        if line.strip().startswith("```mermaid"):
            in_block = True
            current_code = []
            continue
        if in_block and line.strip().startswith("```"):
            in_block = False
            block_index += 1
            blocks.append((block_index, current_title or f"Diagram_{block_index}", "\n".join(current_code)))
            current_title = None
            current_code = []
            continue
        if in_block:
            current_code.append(line)
            continue
        title_match = re.match(r'^##+\s+(.*)', line)
        if title_match:
            current_title = title_match.group(1).strip()

    return blocks


def main():
    with open(MD_PATH, "r", encoding="utf-8") as f:
        md_content = f.read()

    blocks = extract_mermaid_blocks(md_content)
    print(f"📊 找到 {len(blocks)} 个 Mermaid 图\n")

    for idx, title, code in blocks:
        safe_title = re.sub(r'[^\w\u4e00-\u9fff-]', '_', title)[:40]
        filename = f"{idx:02d}_{safe_title}.mmd"
        output_path = os.path.join(OUTPUT_DIR, filename)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(code.strip() + "\n")
        print(f"  ✅ {output_path}")

    print(f"\n🎉 已提取到: {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
