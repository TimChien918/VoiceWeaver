#!/usr/bin/env python3
"""產生 DMG 安裝視窗背景圖（拖移到「應用程式」的說明畫面）。
用法：python make_background.py /輸出/background.png
視窗 660x400；App 圖示在左(約 x=180)、應用程式在右(約 x=480)，中間畫箭頭。
同時輸出 @2x（background@2x.png）供 HiDPI。
"""
import sys
from PIL import Image, ImageDraw, ImageFont

W, H = 660, 400


def font(size):
    for p in ("/System/Library/Fonts/PingFang.ttc",
              "/System/Library/Fonts/STHeiti Medium.ttc",
              "/System/Library/Fonts/Helvetica.ttc"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            pass
    return ImageFont.load_default()


def render(scale: int) -> Image.Image:
    w, h = W * scale, H * scale
    img = Image.new("RGBA", (w, h), (246, 246, 248, 255))
    d = ImageDraw.Draw(img)
    # 標題
    title = "安裝 拾語者 語音中心"
    sub = "把左邊的 App 拖到右邊的「應用程式」資料夾即可"
    tf, sf = font(30 * scale), font(17 * scale)
    for text, fnt, y, col in ((title, tf, 44 * scale, (40, 40, 45)),
                              (sub, sf, 92 * scale, (120, 120, 128))):
        tw = d.textlength(text, font=fnt)
        d.text(((w - tw) / 2, y), text, font=fnt, fill=col)
    # 箭頭（從 App 指向 應用程式），y 對齊圖示中心 ~200
    ay = 200 * scale
    x1, x2 = 262 * scale, 398 * scale
    col = (10, 132, 255, 255)
    d.line([(x1, ay), (x2, ay)], fill=col, width=10 * scale)
    d.polygon([(x2, ay - 22 * scale), (x2 + 34 * scale, ay), (x2, ay + 22 * scale)], fill=col)
    return img


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "background.png"
    render(1).save(out)
    at2 = out.replace(".png", "@2x.png")
    render(2).save(at2)
    print("wrote", out, "and", at2)


if __name__ == "__main__":
    main()
