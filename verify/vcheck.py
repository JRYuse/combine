#!/usr/bin/env python3
"""用 deepseek-v4-flash-vision 核对截图（当前模型不支持识图时的替代通道）。

用法：
  python3 verify/vcheck.py /root/sd/shots/039_settings_list.png
  python3 verify/vcheck.py -p "对比这两张：面板数字有没有变化" /root/sd/shots/040_*.png /root/sd/shots/041_*.png

- token 从 /root/.codex/config.toml 的 [model_providers.intern] 读，不打印、不落盘。
- 一次请求传所有给定图片（适合前后对比）；单张核对就单张传。
- 只打印模型答复文本，不打印请求体。
"""

import argparse
import base64
import json
import sys
import urllib.request

API = "https://discovery-api.intern-ai.org.cn/v1/chat/completions"
MODEL = "deepseek-v4-flash-vision"


def read_token():
    tok = None
    in_intern = False
    with open("/root/.codex/config.toml") as f:
        for line in f:
            s = line.strip()
            if s.startswith("[model_providers.intern]"):
                in_intern = True
            elif s.startswith("["):
                in_intern = False
            elif in_intern and s.startswith("experimental_bearer_token"):
                tok = s.split("=", 1)[1].strip().strip('"')
    if not tok:
        sys.exit("找不到 intern provider 的 token（/root/.codex/config.toml）")
    return tok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="+")
    ap.add_argument(
        "-p",
        "--prompt",
        default=(
            "用一句话描述画面，并列出有没有文字裁切/重叠/按钮被裁/超出屏幕等布局问题；"
            "只给结论，不要思考过程，200字以内。"
        ),
    )
    args = ap.parse_args()

    parts = [{"type": "text", "text": args.prompt}]
    for pth in args.images:
        ext = pth.rsplit(".", 1)[-1].lower() if "." in pth else "png"
        with open(pth, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        parts.append(
            {"type": "image_url", "image_url": {"url": f"data:image/{ext};base64," + b64}}
        )

    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": parts}],
        "max_tokens": 700,
    }
    req = urllib.request.Request(
        API,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + read_token()},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        resp = json.load(r)
    msg = resp["choices"][0]["message"]
    out = msg.get("content") or msg.get("reasoning_content") or ""
    print(out.strip() if out else json.dumps(msg, ensure_ascii=False))


if __name__ == "__main__":
    main()
