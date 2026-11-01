"""產生拾語者 Speak My Life 語音中心的 Windows 圖示（聲波長條，藍青漸層）。"""
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit(0)

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("AppIcon.ico")

def make_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size
    # 背景圓角矩形（深藍）
    r = s // 6
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=(18, 28, 58, 255))
    # 聲波長條（5 條，藍青漸層）
    bars = 5
    colors = ["#00d4ff", "#00b8e6", "#0096cc", "#0074b3", "#005299"]
    heights = [0.45, 0.70, 0.90, 0.70, 0.45]
    total_w = s * 0.60
    bar_w = total_w / (bars * 2 - 1)
    gap = bar_w
    start_x = (s - total_w) / 2
    cx_y = s / 2
    for i in range(bars):
        x = start_x + i * (bar_w + gap)
        h = heights[i] * s * 0.65
        y0 = cx_y - h / 2
        y1 = cx_y + h / 2
        d.rounded_rectangle([x, y0, x + bar_w, y1], radius=bar_w / 3, fill=colors[i])
    return img

sizes = [16, 32, 48, 64, 128, 256]
# 以最大尺寸為基準，讓 Pillow 內嵌各解析度（用最小尺寸當基準會讓 ICO 變得極小且模糊）
base = make_icon(256)
base.save(OUT, format="ICO", sizes=[(s, s) for s in sizes])
print(f"圖示已產生：{OUT}")
