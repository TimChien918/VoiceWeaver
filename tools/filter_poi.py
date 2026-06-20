#!/usr/bin/env python3
"""
過濾 Overpass JSON → 精簡 TSV，給 OfflinePoiIndex.kt 讀取。

格式（每行）:  lat\tlon\tcategory\tname

關鍵設計：除了 amenity/shop/healthcare，**還會掃 brand= 與 name=** 抓連鎖店。
這是因為 OSM 台灣的便利商店 / 速食店常常只標 brand=7-Eleven 而沒標 amenity=convenience，
舊版只看 amenity/shop 會漏掉約 4000 家店。

不 gzip：Android AAPT 看到 `.gz` 副檔名會自動解壓，反而會搞砸。

用法:
    python3 tools/filter_poi.py raw.json output.tsv
"""
import json, sys
from pathlib import Path

# OSM 標籤 → 中文類別。amenity / shop / healthcare key value 對照。
TAG_MAP = {
    "hospital":"醫院","clinic":"診所","doctors":"診所","dentist":"牙科","pharmacy":"藥局",
    "veterinary":"獸醫","physiotherapist":"復健科","optometrist":"眼鏡行",
    "school":"學校","kindergarten":"幼兒園","college":"學院","university":"大學",
    "library":"圖書館","language_school":"語言補習班","music_school":"音樂教室","driving_school":"駕訓班",
    "restaurant":"餐廳","food_court":"美食街","fast_food":"速食店","cafe":"咖啡店",
    "bar":"酒吧","pub":"酒吧","ice_cream":"冰品店","biergarten":"啤酒屋",
    "bank":"銀行","atm":"ATM","post_office":"郵局","post_box":"郵筒",
    "police":"警察局","fire_station":"消防隊","townhall":"市政府","courthouse":"法院",
    "bus_station":"車站","fuel":"加油站","charging_station":"充電站","parking":"停車場",
    "car_wash":"洗車場","taxi":"計程車招呼站","ferry_terminal":"渡輪碼頭",
    "place_of_worship":"廟宇","marketplace":"市場","cinema":"電影院","theatre":"劇院",
    "nightclub":"夜店","community_centre":"社區中心","toilets":"公廁","shelter":"庇護所",
    "information":"資訊站",
    "hotel":"旅館","motel":"汽車旅館","hostel":"青年旅館","guest_house":"民宿",
    "museum":"博物館","gallery":"藝廊","attraction":"景點","viewpoint":"觀景點",
    "convenience":"便利商店","supermarket":"超市","wholesale":"量販店",
    "department_store":"百貨","mall":"購物中心","kiosk":"小賣店","general":"雜貨店",
    "bakery":"麵包店","pastry":"糕餅店","confectionery":"糖果店",
    "butcher":"肉品店","seafood":"海鮮店","greengrocer":"蔬果店","beverages":"飲料店",
    "alcohol":"酒類專賣店","tea":"茶葉店","coffee":"咖啡豆店",
    "clothes":"服飾店","shoes":"鞋店","bag":"包包店","jewelry":"珠寶店",
    "watches":"鐘錶店","tailor":"裁縫店","hairdresser":"美髮","beauty":"美容院",
    "cosmetics":"化妝品店","perfumery":"香水店",
    "electronics":"電子產品店","mobile_phone":"手機店","computer":"電腦店",
    "appliance":"家電行","hifi":"音響店",
    "hardware":"五金行","doityourself":"DIY 居家用品店","furniture":"家具店",
    "florist":"花店","garden_centre":"園藝店",
    "books":"書店","stationery":"文具店","music":"唱片行","art":"藝品店",
    "toys":"玩具店","gift":"禮品店","pet":"寵物店",
    "sports":"運動用品店","bicycle":"自行車店","outdoor":"戶外用品店",
    "car":"汽車經銷商","car_repair":"汽車維修","motorcycle":"機車行","tyres":"輪胎行",
    "optician":"眼鏡行","medical_supply":"醫療用品店","massage":"按摩店",
    "laundry":"洗衣店","dry_cleaning":"乾洗店","copyshop":"影印店",
    "tobacco":"菸酒店","lottery":"彩券行","estate_agent":"房仲","insurance":"保險公司",
    "travel_agency":"旅行社","pawnbroker":"當鋪","funeral_directors":"葬儀社",
    "vending_machine":"自動販賣機"
}

# 連鎖品牌名稱 → 中文類別。比對 brand / operator / name 三個欄位（任一命中就算）。
# **大寫無關**比對。長關鍵字優先（"7-Eleven" 比 "全家" 先檢查，避免子字串誤判）。
BRAND_MAP = [
    # 便利商店
    ("7-eleven", "便利商店"), ("7-11", "便利商店"), ("seven-eleven", "便利商店"),
    ("統一超商", "便利商店"), ("全家", "便利商店"), ("familymart", "便利商店"),
    ("萊爾富", "便利商店"), ("hi-life", "便利商店"), ("ok mart", "便利商店"),
    # 速食
    ("麥當勞", "速食店"), ("mcdonald", "速食店"),
    ("肯德基", "速食店"), ("kfc", "速食店"),
    ("摩斯", "速食店"), ("mos burger", "速食店"), ("mosburger", "速食店"),
    ("漢堡王", "速食店"), ("burger king", "速食店"),
    ("subway", "速食店"), ("賽百味", "速食店"),
    # 咖啡
    ("星巴克", "咖啡店"), ("starbucks", "咖啡店"),
    ("路易莎", "咖啡店"), ("louisa", "咖啡店"),
    ("85度c", "咖啡店"), ("85°c", "咖啡店"),
    ("cama", "咖啡店"),
    # 飲料
    ("50嵐", "飲料店"), ("五十嵐", "飲料店"), ("coco", "飲料店"),
    ("迷客夏", "飲料店"), ("可不可", "飲料店"), ("kebuke", "飲料店"),
    ("茶湯會", "飲料店"), ("天仁", "飲料店"),
    ("麻古", "飲料店"), ("macu", "飲料店"), ("得正", "飲料店"),
    ("茶之魔手", "飲料店"), ("貢茶", "飲料店"), ("gong cha", "飲料店"),
    # 量販/超市
    ("全聯", "超市"), ("px mart", "超市"), ("pxmart", "超市"),
    ("家樂福", "超市"), ("carrefour", "超市"),
    ("好市多", "超市"), ("costco", "超市"),
    ("大潤發", "超市"), ("rt-mart", "超市"),
    # 藥妝
    ("屈臣氏", "藥妝店"), ("watsons", "藥妝店"),
    ("康是美", "藥妝店"), ("cosmed", "藥妝店"),
    ("大樹藥局", "藥局"), ("杏一", "藥局"),
    # 百貨
    ("新光三越", "百貨"), ("sogo", "百貨"),
    ("微風", "百貨"), ("遠百", "百貨"), ("遠東百貨", "百貨"),
    ("誠品", "百貨"),
    # 銀行
    ("玉山銀行", "銀行"), ("中信銀行", "銀行"), ("國泰世華", "銀行"),
    ("兆豐銀行", "銀行"), ("第一銀行", "銀行"), ("華南銀行", "銀行"),
    ("彰銀", "銀行"), ("永豐銀行", "銀行"), ("台新銀行", "銀行"),
    # 加油
    ("中油", "加油站"), ("台塑", "加油站"), ("台亞", "加油站"),
    # ────────────────── 美國連鎖（給海外使用者 / 旅居者用）──────────────────
    # 便利商店
    ("7-eleven", "便利商店"),   # 已在前面但保留以防遺漏
    ("circle k", "便利商店"), ("wawa", "便利商店"), ("sheetz", "便利商店"),
    ("speedway", "便利商店"), ("am/pm", "便利商店"), ("ampm", "便利商店"),
    # 超市/量販
    ("walmart", "超市"), ("target", "超市"),
    ("trader joe", "超市"), ("whole foods", "超市"), ("safeway", "超市"),
    ("kroger", "超市"), ("publix", "超市"), ("aldi", "超市"),
    ("h-e-b", "超市"), ("heb", "超市"), ("wegmans", "超市"), ("food lion", "超市"),
    ("stop & shop", "超市"), ("ralphs", "超市"), ("vons", "超市"),
    ("sam's club", "超市"), ("sams club", "超市"), ("bjs wholesale", "超市"),
    # 速食
    ("mcdonald", "速食店"), ("burger king", "速食店"), ("wendy", "速食店"),
    ("taco bell", "速食店"), ("chick-fil-a", "速食店"), ("chickfila", "速食店"),
    ("popeyes", "速食店"), ("arby", "速食店"), ("jack in the box", "速食店"),
    ("white castle", "速食店"), ("five guys", "速食店"), ("in-n-out", "速食店"),
    ("in n out", "速食店"), ("sonic drive", "速食店"), ("hardee", "速食店"),
    ("carl's jr", "速食店"), ("dairy queen", "速食店"), ("panda express", "速食店"),
    ("chipotle", "速食店"), ("qdoba", "速食店"), ("panera", "速食店"),
    ("pizza hut", "速食店"), ("papa john", "速食店"), ("domino", "速食店"),
    ("little caesars", "速食店"),
    # 咖啡/飲料
    ("starbucks", "咖啡店"), ("dunkin", "咖啡店"), ("dunkin' donuts", "咖啡店"),
    ("peet's coffee", "咖啡店"), ("tim hortons", "咖啡店"),
    ("blue bottle", "咖啡店"), ("philz", "咖啡店"), ("caribou coffee", "咖啡店"),
    # 藥局/藥妝
    ("cvs", "藥局"), ("walgreens", "藥局"), ("rite aid", "藥局"),
    ("duane reade", "藥局"),
    # 居家/五金
    ("home depot", "五金行"), ("lowe", "五金行"), ("ace hardware", "五金行"),
    ("ikea", "家具店"), ("bed bath", "居家用品店"),
    # 3C/電器
    ("best buy", "電子產品店"), ("apple store", "電子產品店"),
    ("microsoft store", "電子產品店"), ("gamestop", "電玩店"),
    # 百貨/購物
    ("macy", "百貨"), ("nordstrom", "百貨"), ("kohl", "百貨"),
    ("jcpenney", "百貨"), ("bloomingdale", "百貨"), ("saks", "百貨"),
    ("dillard", "百貨"), ("tj maxx", "百貨"), ("marshalls", "百貨"),
    ("ross dress", "百貨"), ("nordstrom rack", "百貨"),
    # 服飾
    ("gap", "服飾店"), ("old navy", "服飾店"), ("h&m", "服飾店"),
    ("uniqlo", "服飾店"), ("zara", "服飾店"), ("forever 21", "服飾店"),
    ("american eagle", "服飾店"), ("hollister", "服飾店"),
    ("victoria's secret", "服飾店"), ("urban outfitters", "服飾店"),
    ("banana republic", "服飾店"), ("levi", "服飾店"),
    # 鞋
    ("foot locker", "鞋店"), ("dsw", "鞋店"), ("famous footwear", "鞋店"),
    # 加油
    ("shell", "加油站"), ("exxon", "加油站"), ("mobil", "加油站"),
    ("chevron", "加油站"), ("bp", "加油站"), ("arco", "加油站"),
    ("76 ", "加油站"), ("valero", "加油站"), ("sunoco", "加油站"),
    ("phillips 66", "加油站"), ("conoco", "加油站"), ("texaco", "加油站"),
    ("marathon", "加油站"), ("citgo", "加油站"),
    # 銀行
    ("chase", "銀行"), ("bank of america", "銀行"), ("wells fargo", "銀行"),
    ("citibank", "銀行"), ("us bank", "銀行"), ("pnc bank", "銀行"),
    ("td bank", "銀行"), ("capital one", "銀行"), ("hsbc", "銀行"),
    # 郵局
    ("ups store", "郵局"), ("fedex office", "郵局"), ("usps", "郵局"),
    # 旅館
    ("marriott", "旅館"), ("hilton", "旅館"), ("hyatt", "旅館"),
    ("holiday inn", "旅館"), ("best western", "旅館"), ("motel 6", "旅館"),
    ("super 8", "旅館"), ("days inn", "旅館"), ("la quinta", "旅館"),
    ("comfort inn", "旅館"), ("hampton inn", "旅館"), ("courtyard", "旅館"),
    # 健身/書店/其他
    ("planet fitness", "健身房"), ("la fitness", "健身房"),
    ("equinox", "健身房"), ("ymca", "健身房"),
    ("barnes & noble", "書店"), ("books-a-million", "書店"),
    ("petco", "寵物店"), ("petsmart", "寵物店"),
    ("autozone", "汽車零件"), ("o'reilly auto", "汽車零件"),
    ("advance auto", "汽車零件"), ("pep boys", "汽車零件"),
]

KEEP_NAMELESS = {"便利商店","超市","醫院","診所","藥局","加油站","學校","幼兒園","郵局","銀行","ATM","捷運站","車站"}


def classify(tags):
    """回傳 (category_zh, name) 或 None。"""
    # 1. 標準 amenity/shop/healthcare 對映
    for k in ("amenity", "shop", "healthcare"):
        v = tags.get(k)
        if v and v in TAG_MAP:
            zh = TAG_MAP[v]
            name = (tags.get("name:zh") or tags.get("name") or "").strip()
            return zh, name
    # 2. brand / operator / name 比對連鎖店表
    candidates = " ".join(filter(None, [
        tags.get("brand", ""), tags.get("brand:zh", ""),
        tags.get("operator", ""), tags.get("operator:zh", ""),
        tags.get("name", ""), tags.get("name:zh", ""),
    ])).lower()
    for keyword, zh in BRAND_MAP:
        if keyword in candidates:
            # 用 brand:zh 優先當名稱（最乾淨），fallback 到 name:zh > name > brand
            name = (tags.get("brand:zh") or tags.get("name:zh")
                    or tags.get("name") or tags.get("brand") or "").strip()
            return zh, name
    return None


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} raw.json output.tsv", file=sys.stderr); sys.exit(1)
    with open(sys.argv[1], 'r', encoding='utf-8') as f:
        data = json.load(f)

    rows, raw, seen = [], data["elements"], set()
    for e in raw:
        tags = e.get("tags") or {}
        result = classify(tags)
        if not result: continue
        zh, name = result
        name = name.replace("\t"," ").replace("\n"," ")
        if not name and zh not in KEEP_NAMELESS: continue

        if e["type"] == "node":
            lat, lon = e.get("lat"), e.get("lon")
        else:
            c = e.get("center") or {}; lat, lon = c.get("lat"), c.get("lon")
        if lat is None: continue

        # 同一座標 + 名稱去重（Overpass 多 query 條件會回重複的）
        dedup_key = (round(lat, 5), round(lon, 5), name, zh)
        if dedup_key in seen: continue
        seen.add(dedup_key)

        rows.append(f"{lat:.5f}\t{lon:.5f}\t{zh}\t{name}")

    print(f"Kept {len(rows)} POIs (from {len(raw)} raw elements)", file=sys.stderr)
    # 連鎖店統計（驗證解析有效）
    from collections import Counter
    cnt = Counter(r.split('\t')[2] for r in rows)
    top = cnt.most_common(10)
    print(f"Top categories: {top}", file=sys.stderr)

    out_path = Path(sys.argv[2])
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as out:
        for r in rows: out.write(r + "\n")


if __name__ == "__main__":
    main()
