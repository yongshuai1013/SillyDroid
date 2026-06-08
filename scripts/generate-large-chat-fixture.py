#!/usr/bin/env python3
"""
生成可直接导入 SillyTavern 的超长 JSONL 聊天测试数据。
默认产出 1201 条消息，每条正文精确 3000 个字符。
"""

from __future__ import annotations

import argparse
import json
import uuid
from pathlib import Path


DEFAULT_OUTPUT = (
    Path("artifacts")
    / "generated"
    / "chat-fixtures"
    / "StressTestBot - 1201floors-3000chars.jsonl"
)

PARAGRAPHS = [
    "这是一段用于聊天记录压力测试的正文，专门验证超长消息在导入、渲染、滚动、搜索、备份与恢复链路中的稳定性表现。",
    "文本内容会保持自然语言形态，而不是单纯重复同一个短句，目的是尽量接近真实用户长篇交流时的消息分布与阅读节奏。",
    "每一层楼都会带有当前楼层编号、说话人标记和场景提示，用来观察消息列表在极端数据规模下的定位能力与界面响应情况。",
    "如果后续需要继续扩大规模，可以直接调整消息条数、单条字符数、用户名、角色名和输出文件路径，无需改动宿主运行代码。",
    "这批数据适合用于测试导入速度、初次打开耗时、楼层跳转、关键字检索、聊天备份、日志抓取以及前端长列表渲染表现。",
    "为了让内容更接近真实聊天，这里混合使用描述、说明、复述、转场和结论型句子，避免所有楼层都呈现完全一致的视觉结构。",
    "在移动设备上，大段连续文本通常更容易暴露卡顿、重排、内存波动和滚动锚点异常，因此这里刻意保持正文长度恒定且规模较大。",
    "当测试人员回看这份记录时，可以结合具体楼层编号、消息方向和时间戳，快速复现某一条消息附近的界面行为与上下文状态。",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate a large SillyTavern chat fixture.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Output JSONL path.")
    parser.add_argument("--message-count", type=int, default=1201, help="Total message count.")
    parser.add_argument("--message-length", type=int, default=3000, help="Characters per message.")
    parser.add_argument(
        "--mode",
        choices=("plain", "html", "fenced-html"),
        default="plain",
        help="Message body mode. fenced-html wraps a complete HTML page in a markdown code block.",
    )
    parser.add_argument("--character-name", default="StressTestBot", help="Assistant/character name.")
    parser.add_argument("--user-name", default="StressTestUser", help="User name.")
    parser.add_argument("--start-timestamp", type=int, default=1760000000000, help="First message timestamp in ms.")
    parser.add_argument("--timestamp-step", type=int, default=60000, help="Timestamp increment in ms.")
    return parser.parse_args()


def build_message_body(index: int, speaker: str, target_length: int) -> str:
    prefix = f"第{index + 1}楼｜{speaker}｜超长聊天压力测试正文开始。"
    parts = [prefix]
    cursor = index % len(PARAGRAPHS)

    # 先拼出可读正文，再按字符精确裁切到目标长度，确保每层楼大小稳定。
    while len("".join(parts)) < target_length:
        parts.append(PARAGRAPHS[cursor])
        if len(parts) % 3 == 0:
            parts.append("\n")
        cursor = (cursor + 1) % len(PARAGRAPHS)

    body = "".join(parts)
    return body[:target_length]


def build_html_message_body(index: int, speaker: str, target_length: int) -> str:
    prefix = (
        f'<section class="stress-floor" data-floor="{index + 1}" data-speaker="{speaker}">'
        f'<h3>第{index + 1}楼 HTML 压力测试</h3>'
    )
    suffix = "</section>"
    parts = [prefix]
    block_index = 0

    # 每条消息内制造大量可渲染标签、属性、列表、表格和 inline 样式，专门压 WebView DOM/布局链路。
    current_length = len("".join(parts))
    while current_length + len(suffix) < target_length:
        paragraph = PARAGRAPHS[(index + block_index) % len(PARAGRAPHS)]
        block = (
            '<article class="stress-card" '
            f'data-floor="{index + 1}" data-block="{block_index}">'
            f'<header><strong>{speaker}</strong><span>block-{block_index:04d}</span></header>'
            '<div class="stress-grid">'
            f'<p><em>{paragraph}</em></p>'
            f'<p><code>floor={index + 1}; block={block_index}; payload=html-render-heavy</code></p>'
            '<ul>'
            f'<li>滚动锚点：<span data-anchor="{index + 1}-{block_index}">anchor</span></li>'
            f'<li>嵌套标签：<b>bold</b><i>italic</i><u>underline</u><mark>mark</mark></li>'
            f'<li>长属性：<span title="{paragraph}">{paragraph}</span></li>'
            '</ul>'
            '<table><tbody>'
            f'<tr><td>row</td><td>{block_index}</td><td>{paragraph}</td></tr>'
            f'<tr><td>meta</td><td>{speaker}</td><td>stress-test-html</td></tr>'
            '</tbody></table>'
            '</div>'
            '</article>'
        )
        if current_length + len(block) + len(suffix) > target_length:
            break
        parts.append(block)
        current_length += len(block)
        block_index += 1

    filler_length = target_length - current_length - len(suffix)
    if filler_length > 0:
        parts.append("x" * filler_length)

    return "".join(parts) + suffix


def build_fenced_html_message_body(index: int, speaker: str, target_length: int) -> str:
    prefix = (
        "```HTML\n"
        "<!doctype html>\n"
        f'<html lang="zh-CN" data-floor="{index + 1}" data-speaker="{speaker}">\n'
        "<head>\n"
        '<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        f"<title>第{index + 1}楼渲染压力测试</title>\n"
        "<style>\n"
        ":root{--accent:#d9480f;--ink:#18212f;--paper:#fff8e7;--line:#293241;}\n"
        "body{margin:0;padding:18px;font-family:serif;background:linear-gradient(135deg,#fff8e7,#e6f4f1);color:var(--ink);}\n"
        ".wrap{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));}\n"
        ".card{border:1px solid var(--line);background:rgba(255,255,255,.82);padding:10px;box-shadow:3px 3px 0 rgba(0,0,0,.18);}\n"
        ".card h4{margin:0 0 6px;font-size:15px;color:var(--accent);}\n"
        ".meter{height:8px;background:repeating-linear-gradient(90deg,var(--accent) 0 12px,#246a73 12px 24px);}\n"
        "table{width:100%;border-collapse:collapse;font-size:12px}td,th{border:1px solid #697268;padding:4px;}\n"
        "@keyframes pulse{from{filter:saturate(1)}to{filter:saturate(1.8)}}.card:nth-child(3n){animation:pulse 900ms alternate infinite;}\n"
        "</style>\n"
        "</head>\n"
        "<body>\n"
        f'<main id="floor-{index + 1}" class="stress-page">\n'
        f"<h1>第{index + 1}楼 HTML 网页渲染压力测试</h1>\n"
        f"<p>speaker={speaker}; mode=fenced-html; payload=style-script-dom-heavy</p>\n"
        '<section class="wrap">\n'
    )
    suffix = (
        "</section>\n"
        "<script>\n"
        "const cards=[...document.querySelectorAll('.card')];\n"
        "let total=0;for(const card of cards){total+=card.textContent.length;card.dataset.total=String(total);}\n"
        "document.body.dataset.renderedCards=String(cards.length);\n"
        "requestAnimationFrame(()=>document.body.classList.add('ready'));\n"
        "</script>\n"
        "</main>\n"
        "</body>\n"
        "</html>\n"
        "```"
    )
    parts = [prefix]
    current_length = len(prefix)
    block_index = 0

    # 这里刻意生成完整网页片段，让酒馆助手的 ```HTML``` 渲染链路同时处理 CSS、DOM 和 JS。
    while current_length + len(suffix) < target_length:
        paragraph = PARAGRAPHS[(index + block_index) % len(PARAGRAPHS)]
        block = (
            f'<article class="card" data-floor="{index + 1}" data-card="{block_index}">\n'
            f"<h4>区块 {block_index:04d}</h4>\n"
            f"<p>{paragraph}</p>\n"
            '<div class="meter" aria-hidden="true"></div>\n'
            "<ul>\n"
            f"<li><strong>floor</strong>: {index + 1}</li>\n"
            f"<li><em>block</em>: {block_index}</li>\n"
            f"<li><code>render-target=assistant-html-preview</code></li>\n"
            "</ul>\n"
            "<table><tbody>\n"
            f"<tr><th>字段</th><th>值</th><th>说明</th></tr>\n"
            f"<tr><td>speaker</td><td>{speaker}</td><td>{paragraph}</td></tr>\n"
            f"<tr><td>payload</td><td>{block_index}</td><td>style script table list nested dom</td></tr>\n"
            "</tbody></table>\n"
            "</article>\n"
        )
        if current_length + len(block) + len(suffix) > target_length:
            break
        parts.append(block)
        current_length += len(block)
        block_index += 1

    filler_length = target_length - current_length - len(suffix)
    if filler_length > 0:
        parts.append("x" * filler_length)

    return "".join(parts) + suffix


def build_header() -> dict:
    return {
        "chat_metadata": {
            "integrity": str(uuid.uuid4()),
            "scenario": "超长聊天压力测试",
            "persona": "stress-test",
            "fixture": {
                "type": "large-chat",
                "messageCount": 1201,
                "messageLength": 3000,
                "mode": "plain",
            },
        },
        "user_name": "unused",
        "character_name": "unused",
    }


def build_messages(args: argparse.Namespace) -> list[dict]:
    messages: list[dict] = []
    for index in range(args.message_count):
        is_user = index % 2 == 0
        speaker = args.user_name if is_user else args.character_name
        messages.append(
            {
                "name": speaker,
                "is_user": is_user,
                "send_date": args.start_timestamp + index * args.timestamp_step,
                "mes": (
                    build_fenced_html_message_body(index, speaker, args.message_length)
                    if args.mode == "fenced-html"
                    else build_html_message_body(index, speaker, args.message_length)
                    if args.mode == "html"
                    else build_message_body(index, speaker, args.message_length)
                ),
            }
        )
    return messages


def main() -> None:
    args = parse_args()
    output_path = args.output.resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    header = build_header()
    header["chat_metadata"]["fixture"]["messageCount"] = args.message_count
    header["chat_metadata"]["fixture"]["messageLength"] = args.message_length
    header["chat_metadata"]["fixture"]["mode"] = args.mode

    lines = [json.dumps(header, ensure_ascii=False)]
    messages = build_messages(args)
    lines.extend(json.dumps(message, ensure_ascii=False) for message in messages)
    output_path.write_text("\n".join(lines), encoding="utf-8", newline="\n")

    print(f"generated={output_path}")
    print(f"message_count={args.message_count}")
    print(f"message_length={args.message_length}")
    print(f"mode={args.mode}")
    print(f"first_message_chars={len(messages[0]['mes']) if messages else 0}")


if __name__ == "__main__":
    main()
