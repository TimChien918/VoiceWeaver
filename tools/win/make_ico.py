#!/usr/bin/env python3
"""產生 VoiceWeaver 語音中心 App 圖示（AppIcon.ico，Windows 用）。
用法：python make_ico.py /輸出/AppIcon.ico
設計與 macOS 版（tools/dmg/make_icon.py）一致：藍青漸層圓角方塊 + 白色聲波長條。
"""
import sys

from PIL import Image, ImageDraw

S = 1024


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def make_master() -> Image.Image:
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # 垂直漸層（上：青 #2BD0E0 → 下：藍 #0A84FF）
    top, bot = (43, 208, 224), (10, 132, 255)
    grad = Image.new("RGBA", (S, S))
    gpx = grad.load()
    for y in range(S):
        c = lerp(top, bot, y / (S - 1))
        for x in range(S):
            gpx[x, y] = (c[0], c[1], c[2], 255)
    # 圓角方塊遮罩
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    r = int(S * 0.225)
    md.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=255)
    img.paste(grad, (0, 0), mask)

    # 白色聲波長條（5 條，圓頭）
    d = ImageDraw.Draw(img)
    heights = [0.34, 0.58, 0.92, 0.58, 0.34]
    bar_w = int(S * 0.085)
    gap = int(S * 0.06)
    total_w = len(heights) * bar_w + (len(heights) - 1) * gap
    x0 = (S - total_w) // 2
    cy = S // 2
    white = (255, 255, 255, 240)
    for i, h in enumerate(heights):
        bh = int(S * 0.5 * h)
        x = x0 + i * (bar_w + gap)
        d.rounded_rectangle([x, cy - bh // 2, x + bar_w, cy + bh // 2],
                            radius=bar_w // 2, fill=white)
    return img


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "AppIcon.ico"
    master = make_master()
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64),
             (128, 128), (256, 256)]
    master.save(out, format="ICO", sizes=sizes)
    print("wrote", out)


if __name__ == "__main__":
    main()
