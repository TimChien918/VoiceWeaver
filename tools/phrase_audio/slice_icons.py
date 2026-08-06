#!/usr/bin/env python3
"""把繪圖 AI 產出的 4 張拼圖切成 30 個獨立圖示。

處理三件事，每一件都是實際拿到的圖有的問題：

1. **格線**：有幾張帶淡灰分隔線。純粹按格數等分會把線切進圖示邊緣，
   所以每格先往內縮一段再處理。
2. **"URGENT" 字樣**：chest 與 breathe 兩格被畫上了英文字。圖卡的使用者
   本來就可能讀不了字（那正是需要圖卡的原因），而且模型畫的字常常是壞的。
   這兩格先把上緣裁掉再處理。
3. **大小不一**：每個圖示在自己格子裡的佔比差很多。直接輸出會讓網格看起來忽大忽小，
   所以一律用內容邊界重新置中、縮放到同一個佔比。

背景做成透明：外框往內 flood fill，只吃掉圖示外面的白，圖示內部的白留著。
不這樣做的話深色主題下每張圖都會是一個白方塊。
"""
import os
from collections import deque

from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
UPLOAD = os.path.expanduser(
    "~/.claude/uploads/0eff1012-83ef-429c-9549-9f34a5708ec5")
OUT = os.path.join(ROOT, "..", "..", "app", "src", "main", "assets", "phrase_icons")

SIZE = 288          # 96dp @ 3x
CONTENT = 0.80      # 圖示內容佔輸出邊長的比例
INSET = 0.03        # 每格往內縮多少，用來吃掉格線
WHITE_TOL = 26      # 判定為背景白的容差

# 每張圖的 8 格由左至右、由上至下。None＝該格留白，不輸出。
SHEETS = [
    ("73a6e99d-IMG_1460.png",
     ["water", "food", "bathroom", "rest", "cold", "hot", "sit", "pain"], set()),
    ("b6719d48-IMG_1461.png",
     ["head", "chest", "medication", "doctor", "nurse", "dizzy", "breathe", "help"],
     {1, 6}),          # 這兩格上緣有 "URGENT" 字樣
    ("6d5f299e-IMG_1462.png",
     ["wait", "again", "slower", "write", "understand", "family", "home", None], set()),
    ("5d9e3c90-IMG_1463.png",
     ["alone", "stay", "yes", "no", "thanks", "dontknow", "finished", None], set()),
]
URGENT_TRIM = 0.26     # 帶字的格子，上緣裁掉的比例


def is_white(px):
    return px[0] > 255 - WHITE_TOL and px[1] > 255 - WHITE_TOL and px[2] > 255 - WHITE_TOL


def strip_background(img):
    """從四邊往內 flood fill，把連通到邊界的白色變透明。

    用 flood fill 而不是「所有白色都變透明」：圖示內部大量使用白色填色
    （杯子、盤子、紙張），一律去掉會把圖示挖空。
    """
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load()
    seen = [[False] * h for _ in range(w)]
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_white(px[x, y]) and not seen[x][y]:
                seen[x][y] = True; q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_white(px[x, y]) and not seen[x][y]:
                seen[x][y] = True; q.append((x, y))
    while q:
        x, y = q.popleft()
        px[x, y] = (255, 255, 255, 0)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and not seen[nx][ny] and is_white(px[nx, ny]):
                seen[nx][ny] = True; q.append((nx, ny))
    return img


def normalize(cell):
    """去背 → 取內容邊界 → 置中縮放到統一佔比。"""
    img = strip_background(cell)
    bbox = img.getbbox()
    if not bbox:
        return None
    img = img.crop(bbox)

    target = int(SIZE * CONTENT)
    scale = min(target / img.width, target / img.height)
    img = img.resize(
        (max(1, round(img.width * scale)), max(1, round(img.height * scale))),
        Image.LANCZOS)

    canvas = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
    canvas.paste(img, ((SIZE - img.width) // 2, (SIZE - img.height) // 2), img)
    return canvas


def main():
    os.makedirs(OUT, exist_ok=True)
    written = []
    for fname, names, urgent in SHEETS:
        sheet = Image.open(os.path.join(UPLOAD, fname)).convert("RGB")
        w, h = sheet.size
        cw, ch = w / 4, h / 2
        for i, name in enumerate(names):
            if name is None:
                continue
            col, row = i % 4, i // 4
            ix, iy = cw * INSET, ch * INSET
            top = ch * row + iy
            if i in urgent:
                top = ch * row + ch * URGENT_TRIM
            box = (round(cw * col + ix), round(top),
                   round(cw * (col + 1) - ix), round(ch * (row + 1) - iy))
            out = normalize(sheet.crop(box))
            if out is None:
                print(f"  ⚠ {name}: 切出來是空的")
                continue
            out.save(os.path.join(OUT, name + ".png"))
            written.append(name)
            print(f"  {name}.png")
    print(f"\n共 {len(written)} 個")


if __name__ == "__main__":
    main()
