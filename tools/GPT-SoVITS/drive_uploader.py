#!/usr/bin/env python3
"""把曲庫上傳到「使用者自己的」Google Drive（OAuth，drive.file 範圍）。

- 每個使用者用自己的 Google 帳號登入授權 → 檔案進他自己的 Drive，不經開發者。
- drive.file 範圍：App 只能看到／管理「它自己建立的檔案」，碰不到使用者 Drive 其他東西
  （最保護隱私、也免 Google 敏感權限審核）。App 建立的資料夾樹在重跑時找得回來（冪等）。
- 與 Colab 一致：Colab drive.mount 掛的是同一位登入者的 Drive → 上傳到這、Colab 讀得到。

憑證檔位置（App 執行時讀，密鑰不進原始碼）：
  client_secret：環境變數 VOICEWEAVER_GOOGLE_CLIENT_SECRET，或
                 ~/Library/Application Support/VoiceWeaver/google_client_secret.json，或本檔同目錄。
  token（登入後自動存）：~/Library/Application Support/VoiceWeaver/google_token.json
"""
import json
import os
import time
import urllib.request

# 一次登入要到「Drive + 身分」兩件事：drive.file 給曲庫上傳，openid/email/profile 換 id_token
# 再去換 Firebase 身分（signInWithIdp）→ Firebase 與 Drive 共用同一個 Google 帳號。
SCOPES = [
    "https://www.googleapis.com/auth/drive.file",
    "openid",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/userinfo.profile",
]
FOLDER_MIME = "application/vnd.google-apps.folder"
ROOT_FOLDER = "VoiceWeaver"                          # Drive 上曲庫根資料夾名
MODELS_DIR = "Models"                                # 語音模型（GPT-SoVITS 聲文權重）
ACOUSTIC_DIR = "AcousticModels"                      # 聲波模型（專屬發音的關鍵字樣板）
LANG_DIRS = {"ZH", "EN", "JA", "KO"}
_APP_DIR = os.path.expanduser("~/Library/Application Support/VoiceWeaver")

# Firebase Web API Key 依設計是公開識別碼（網頁版 config.js 本來就明文內嵌），
# 安全性靠 Auth + Firestore 規則，不是靠藏這把 key。放預設值讓安裝即可用。
FIREBASE_API_KEY = os.environ.get("VW_FIREBASE_WEB_API_KEY", "AIzaSyDawUNHovFwAjjxy3jfs55aSOjEnrtk4qQ")
FIREBASE_PROJECT = os.environ.get("VW_FIREBASE_PROJECT_ID", "voiceweaver-af4da")
# 與 firebase_ip_beacon 共用同一個 token 檔 → 登入後 beacon 也變成「這個 Google 帳號」的身分
_BEACON_TOKEN = os.path.join(os.path.expanduser("~/.voiceweaver"), "beacon_token.json")


def _here():
    return os.path.dirname(os.path.abspath(__file__))


def _em():
    """取 emotion_models 模組（拿不到回 None）。資料夾版面的規則只寫在那一支。"""
    try:
        import sys
        if _here() not in sys.path:
            sys.path.insert(0, _here())
        import emotion_models
        return emotion_models
    except Exception:  # noqa: BLE001
        return None


def parse_ref_filename(fname):
    """『【情緒】逐字稿.wav』→ (canon情緒, 逐字稿)。沿用 emotion_models 同一套解析。"""
    em = _em()
    if em:
        return em.parse_ref_filename(fname)
    return "中立", os.path.splitext(fname)[0]


def local_models_dir(local_root):
    """本機快取裡語音模型的實際位置（新版面 <root>/Models，舊版面 <root>）。"""
    em = _em()
    return em.models_root(local_root) if em else local_root


def client_secret_path():
    """找 client_secret.json：環境變數 → AppSupport → 本檔同目錄。回路徑或 ""。"""
    cand = [os.environ.get("VOICEWEAVER_GOOGLE_CLIENT_SECRET", ""),
            os.path.join(_APP_DIR, "google_client_secret.json"),
            os.path.join(_here(), "google_client_secret.json")]
    for p in cand:
        if p and os.path.isfile(p):
            return p
    return ""


def token_path():
    os.makedirs(_APP_DIR, exist_ok=True)
    return os.path.join(_APP_DIR, "google_token.json")


def deps_ok():
    try:
        import googleapiclient  # noqa: F401
        import google_auth_oauthlib  # noqa: F401
        return True
    except Exception:
        return False


# ── 認證 ─────────────────────────────────────────────
def _load_saved():
    """讀本機 token。**scope 不足就當作沒登入**——否則舊 token（只有 drive.file）
    會被當成有效，永遠不會重新登入、Firebase 也永遠連不上（登入合一失效）。

    注意：不能用 creds.has_scopes()——from_authorized_user_file(tp, SCOPES) 會把
    creds.scopes 直接覆寫成傳入的 SCOPES，比對必然成立。要讀 token 檔自己記的 scopes。
    """
    from google.oauth2.credentials import Credentials
    tp = token_path()
    if not os.path.isfile(tp):
        return None
    try:
        with open(tp, encoding="utf-8") as f:
            saved = set(json.load(f).get("scopes") or [])
    except Exception:
        return None
    if not set(SCOPES).issubset(saved):
        return None          # scope 不夠 → 視為未登入，強制重新授權拿 Drive+身分
    try:
        return Credentials.from_authorized_user_file(tp, SCOPES)
    except Exception:
        return None


def is_signed_in():
    """已有可用（可自動更新）且 scope 足夠的憑證＝已登入，不需再開瀏覽器。"""
    creds = _load_saved()
    return bool(creds and (creds.valid or creds.refresh_token))


def get_credentials(interactive=True):
    """回可用憑證；沒有且 interactive 時開瀏覽器讓使用者登入自己的 Google。失敗回 None。"""
    from google.auth.transport.requests import Request
    creds = _load_saved()
    if creds and creds.expired and creds.refresh_token:
        try:
            creds.refresh(Request())
        except Exception:
            creds = None
    if creds and creds.valid:
        # 自我修復：已有 Drive 憑證但 Firebase 還沒連結（例如上次連結遇到 SSL 失敗）
        # → 不必重開瀏覽器，直接補連結。
        if not (firebase_identity() or {}).get("uid"):
            firebase_sign_in_with_google(creds)
        return creds
    if not interactive:
        return None
    secret = client_secret_path()
    if not secret:
        raise FileNotFoundError(
            "找不到 google_client_secret.json（放到 "
            f"{os.path.join(_APP_DIR, 'google_client_secret.json')} 或設 VOICEWEAVER_GOOGLE_CLIENT_SECRET）")
    from google_auth_oauthlib.flow import InstalledAppFlow
    flow = InstalledAppFlow.from_client_secrets_file(secret, SCOPES)
    creds = flow.run_local_server(port=0, prompt="consent",
                                  authorization_prompt_message="請在開啟的瀏覽器登入你自己的 Google 帳號並授權…")
    with open(token_path(), "w", encoding="utf-8") as f:
        f.write(creds.to_json())
    try:
        os.chmod(token_path(), 0o600)
    except OSError:
        pass
    # 同一次登入順便換 Firebase 身分 → Firebase 與 Drive 共用同一個 Google 帳號（登入合一）
    ok, msg = firebase_sign_in_with_google(creds)
    if not ok:
        # 不要靜默失敗：Drive 仍可用，但要讓呼叫端/使用者知道 Firebase 沒合上
        print(f"[drive_uploader] Firebase 連結失敗（Drive 仍可用）：{msg}", flush=True)
    return creds


def sign_out():
    """刪除本機 token（Drive + Firebase 都登出，下次要重新登入）。"""
    for p in (token_path(), _BEACON_TOKEN):
        try:
            os.remove(p)
        except OSError:
            pass


# ── Firebase 身分：與 Drive 同一次 Google 登入 ──────────────
def _ssl_ctx():
    """用 certifi 的 CA bundle 建 SSL context。

    conda/框架版 Python 的 urllib 常沒配好系統 CA → 打 googleapis 會
    CERTIFICATE_VERIFY_FAILED（這正是 Firebase 連結一直靜默失敗的原因）。
    """
    import ssl
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


def firebase_sign_in_with_google(creds):
    """拿 Google id_token 去換 Firebase 身分（signInWithIdp），寫進 beacon token 檔。

    效果：Mac 的 Firebase 身分不再是「匿名」，而是與 Drive 同一個 Google 帳號
    → 與手機／網頁同帳號互通（devices/{uid}、設定同步都對得起來）。
    回 (ok, uid_or_error)。
    """
    id_token = getattr(creds, "id_token", None)
    if not id_token:
        # to_json() 不會保存 id_token；refresh 一次即可重新取得（需 openid 範圍）
        try:
            from google.auth.transport.requests import Request
            creds.refresh(Request())
            id_token = getattr(creds, "id_token", None)
        except Exception as e:  # noqa: BLE001
            return False, f"取 id_token 失敗：{e}"
    if not id_token:
        return False, "Google 未回傳 id_token（缺 openid 範圍？）"
    try:
        body = json.dumps({
            "postBody": f"id_token={id_token}&providerId=google.com",
            "requestUri": "http://localhost",
            "returnIdpCredential": True,
            "returnSecureToken": True,
        }).encode()
        req = urllib.request.Request(
            f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key={FIREBASE_API_KEY}",
            data=body, headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=15, context=_ssl_ctx()) as r:
            res = json.load(r)
        os.makedirs(os.path.dirname(_BEACON_TOKEN), exist_ok=True)
        with open(_BEACON_TOKEN, "w", encoding="utf-8") as f:
            json.dump({"idToken": res["idToken"], "refreshToken": res["refreshToken"],
                       "localId": res["localId"], "email": res.get("email", ""),
                       "provider": "google.com"}, f)
        try:
            os.chmod(_BEACON_TOKEN, 0o600)
        except OSError:
            pass
        return True, res.get("email") or res["localId"]
    except Exception as e:  # noqa: BLE001
        return False, str(e)


def firebase_identity():
    """回目前 Firebase 身分 {uid, email, provider}；未登入回 {}。"""
    try:
        with open(_BEACON_TOKEN, encoding="utf-8") as f:
            d = json.load(f)
        return {"uid": d.get("localId", ""), "email": d.get("email", ""),
                "provider": d.get("provider", "anonymous")}
    except Exception:
        return {}


def account_photo(creds=None) -> str:
    """回登入帳號的大頭照網址（拿不到回 ""）。

    Firebase 的 beacon token 裡沒有存 photoUrl，所以走 Drive 的
    about().user.photoLink——反正要用 Drive 就一定有這組憑證。
    `=s64` 是 Google 的尺寸參數，直接跟它要小圖，免得抓一張大的回來再縮。
    """
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            return ""
        from googleapiclient.discovery import build
        svc = build("drive", "v3", credentials=creds, cache_discovery=False)
        about = svc.about().get(fields="user(photoLink)").execute()
        return about.get("user", {}).get("photoLink", "")
    except Exception:
        return ""


def account_email(creds=None):
    """回登入帳號 email（拿不到回 ""）。"""
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            return ""
        from googleapiclient.discovery import build
        svc = build("drive", "v3", credentials=creds, cache_discovery=False)
        about = svc.about().get(fields="user(emailAddress)").execute()
        return about.get("user", {}).get("emailAddress", "")
    except Exception:
        return ""


# ── Drive 操作 ────────────────────────────────────────
def _service(creds):
    from googleapiclient.discovery import build
    return build("drive", "v3", credentials=creds, cache_discovery=False)


def _retry(fn, tries=5):
    """對 403/429/5xx 做指數退避重試（官方建議，最多 ~32s）。"""
    from googleapiclient.errors import HttpError
    delay = 1.0
    for i in range(tries):
        try:
            return fn()
        except HttpError as e:
            code = getattr(e, "status_code", None) or getattr(getattr(e, "resp", None), "status", None)
            if int(code or 0) in (403, 429, 500, 502, 503) and i < tries - 1:
                time.sleep(min(delay, 32)); delay *= 2; continue
            raise


def _find_folder(svc, name, parent):
    q = (f"name = {_q(name)} and {_q(parent)} in parents and "
         f"mimeType = '{FOLDER_MIME}' and trashed = false")
    r = _retry(lambda: svc.files().list(q=q, spaces="drive", fields="files(id,name)",
                                        pageSize=10).execute())
    files = r.get("files", [])
    return files[0]["id"] if files else None


def _q(s):
    return "'" + str(s).replace("\\", "\\\\").replace("'", "\\'") + "'"


def _ensure_folder(svc, name, parent):
    fid = _find_folder(svc, name, parent)
    if fid:
        return fid
    meta = {"name": name, "mimeType": FOLDER_MIME, "parents": [parent]}
    r = _retry(lambda: svc.files().create(body=meta, fields="id").execute())
    return r["id"]


def _ensure_path(svc, parts):
    """在 My Drive 底下逐層 ensure；回最後一層資料夾 id。"""
    parent = "root"
    for name in parts:
        parent = _ensure_folder(svc, name, parent)
    return parent


def _models_parent(svc, create=False):
    """Drive 上語音模型的父資料夾 id。

    新版面是 VoiceWeaver/Models；舊版面把 ZH/EN/JA/KO 直接掛在 VoiceWeaver 底下，
    那時就回 VoiceWeaver 本身。回 (parent_id, is_legacy)；沒有 VoiceWeaver 回 (None, False)。

    [create] 為真時（上傳路徑）會把 Models/ 建出來——新上傳一律進新版面，
    但**不會**把舊版面的既有角色搬過去，那是 [migrate_drive_layout] 的事。
    """
    root_id = _find_folder(svc, ROOT_FOLDER, "root")
    if not root_id:
        if not create:
            return None, False
        root_id = _ensure_path(svc, [ROOT_FOLDER])
    models_id = _find_folder(svc, MODELS_DIR, root_id)
    if models_id:
        return models_id, False
    # 沒有 Models/：舊版面才會有語言資料夾直接掛在根下。
    # 這種情況連上傳也留在舊版面——同一個曲庫橫跨兩種版面的話，
    # 使用者會在 Drive 上看到同一個角色出現在兩個地方，比沒搬家更難懂。
    legacy = any(f["name"].upper() in LANG_DIRS
                 for f in _list_children(svc, root_id, folders_only=True))
    if legacy:
        return root_id, True
    return (_ensure_folder(svc, MODELS_DIR, root_id) if create else None), False


def migrate_drive_layout(creds=None, progress=None):
    """把 Drive 上舊版面的 VoiceWeaver/<LANG> 搬進 VoiceWeaver/Models/<LANG>。

    **搬的是資料夾的父節點，不是檔案內容**——Drive 的 addParents/removeParents
    只改 metadata，幾十 GB 的曲庫也是幾秒完成，不會重新上傳任何一個位元組。

    回 {ok, moved:[LANG], error}。已經是新版面就 moved=[]。
    """
    res = {"ok": False, "moved": [], "error": ""}
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        root_id = _find_folder(svc, ROOT_FOLDER, "root")
        if not root_id:
            res["ok"] = True; return res          # Drive 上還沒有曲庫，沒得搬
        langs = [f for f in _list_children(svc, root_id, folders_only=True)
                 if f["name"].upper() in LANG_DIRS]
        if not langs:
            res["ok"] = True; return res
        models_id = _ensure_folder(svc, MODELS_DIR, root_id)
        for lf in langs:
            existing = _find_folder(svc, lf["name"], models_id)
            if existing:
                # Models/ 底下已經有同名語言：搬角色進去，不動已存在的同名角色
                for cf in _list_children(svc, lf["id"], folders_only=True):
                    if _find_folder(svc, cf["name"], existing):
                        continue
                    _retry(lambda f=cf: svc.files().update(
                        fileId=f["id"], addParents=existing,
                        removeParents=lf["id"], fields="id").execute())
            else:
                _retry(lambda f=lf: svc.files().update(
                    fileId=f["id"], addParents=models_id,
                    removeParents=root_id, fields="id").execute())
            res["moved"].append(lf["name"].upper())
            if progress:
                progress(lf["name"], 100)
        res["ok"] = True
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


def _find_file(svc, name, parent):
    q = (f"name = {_q(name)} and {_q(parent)} in parents and "
         f"mimeType != '{FOLDER_MIME}' and trashed = false")
    r = _retry(lambda: svc.files().list(q=q, spaces="drive", fields="files(id,name,size)",
                                        pageSize=10).execute())
    files = r.get("files", [])
    return files[0] if files else None


def _upload_file(svc, local, parent, remote_name=None, progress=None):
    """resumable 上傳單檔；同名同大小已存在就跳過。回 ('uploaded'|'skipped', bytes)。"""
    from googleapiclient.http import MediaFileUpload
    name = remote_name or os.path.basename(local)
    size = os.path.getsize(local)
    exist = _find_file(svc, name, parent)
    if exist and str(exist.get("size", "")) == str(size):
        return "skipped", size
    media = MediaFileUpload(local, resumable=True, chunksize=8 * 1024 * 1024)
    body = {"name": name, "parents": [parent]}
    if exist:  # 覆蓋更新（大小不同）
        req = svc.files().update(fileId=exist["id"], media_body=media)
    else:
        req = svc.files().create(body=body, media_body=media, fields="id")
    resp = None
    while resp is None:
        status, resp = _retry(lambda: req.next_chunk())
        if status and progress:
            progress(name, int(status.progress() * 100))
    return "uploaded", size


def upload_character(root, lang, char, progress=None):
    """把本機 <root>/Models/<LANG>/<角色>/ 整包上傳到 Drive VoiceWeaver/Models/<LANG>/<角色>/。

    回 {ok, character, lang, uploaded, skipped, bytes, error}。
    """
    res = {"ok": False, "character": char, "lang": (lang or "").upper(),
           "uploaded": 0, "skipped": 0, "bytes": 0, "error": ""}
    cdir = os.path.join(local_models_dir(root), res["lang"], char)
    if not os.path.isdir(cdir):
        res["error"] = "找不到角色資料夾"; return res
    try:
        creds = get_credentials(interactive=True)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        models_id, _legacy = _models_parent(svc, create=True)
        lang_id = _ensure_folder(svc, res["lang"], models_id)
        leaf = _ensure_folder(svc, char, lang_id)
        for r, _d, files in os.walk(cdir):
            rel = os.path.relpath(r, cdir)
            parent = leaf
            if rel != ".":
                for part in rel.split(os.sep):
                    parent = _ensure_folder(svc, part, parent)
            for f in sorted(files):
                if f == ".DS_Store":
                    continue
                act, b = _upload_file(svc, os.path.join(r, f), parent, progress=progress)
                res["uploaded" if act == "uploaded" else "skipped"] += 1
                res["bytes"] += b
        res["ok"] = True
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


# ── 讀雲端曲庫 / 下載到本機快取（Drive＝主體，本機＝快取）────────
def _list_children(svc, parent, folders_only=False):
    q = f"{_q(parent)} in parents and trashed = false"
    if folders_only:
        q += f" and mimeType = '{FOLDER_MIME}'"
    out, tok = [], None
    while True:
        r = _retry(lambda: svc.files().list(
            q=q, spaces="drive", fields="nextPageToken, files(id,name,size,mimeType)",
            pageSize=200, pageToken=tok).execute())
        out += r.get("files", [])
        tok = r.get("nextPageToken")
        if not tok:
            break
    return out


def is_downloaded(local_root, lang, char, refs_expected=None):
    """本機快取是否已有這個角色（要有 .ckpt + .pth + 至少一顆參考音才算）。"""
    cdir = os.path.join(local_models_dir(local_root), (lang or "").upper(), char)
    if not os.path.isdir(cdir):
        return False
    names = os.listdir(cdir)
    has_gpt = any(n.lower().endswith(".ckpt") for n in names)
    has_sov = any(n.lower().endswith(".pth") for n in names)
    n_refs = sum(1 for _r, _d, fs in os.walk(os.path.join(cdir, "reference_audios"))
                 for f in fs if f.lower().endswith((".wav", ".mp3", ".flac")))
    return bool(has_gpt and has_sov and n_refs)


def list_drive_catalog(local_root=None, creds=None):
    """從 Drive 讀整個曲庫 → 角色清單（含每個角色是否已下載到本機快取）。

    這是「雲端＝唯一曲庫」的資料來源：畫面列什麼由 Drive 決定，不是掃本機。
    回 {ok, characters:[{name,character,lang,emotions[],bytes,files,downloaded}], error}
    """
    local_root = local_root or emotion_root()
    res = {"ok": False, "characters": [], "error": ""}
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"
            return res
        svc = _service(creds)
        models_id, _legacy = _models_parent(svc)
        if not models_id:
            res["ok"] = True          # 已登入但 Drive 上還沒有曲庫（還沒上傳過）
            return res
        for lf in _list_children(svc, models_id, folders_only=True):
            lang = lf["name"].upper()
            if lang not in LANG_DIRS:
                continue
            for cf in _list_children(svc, lf["id"], folders_only=True):
                char = cf["name"]
                total, nfiles, emos = 0, 0, []
                stack = [cf["id"]]
                while stack:                      # 走訪角色資料夾下所有層
                    for f in _list_children(svc, stack.pop()):
                        if f["mimeType"] == FOLDER_MIME:
                            stack.append(f["id"]); continue
                        nfiles += 1
                        total += int(f.get("size") or 0)
                        if f["name"].lower().endswith((".wav", ".mp3", ".flac")):
                            emo, _p = parse_ref_filename(f["name"])
                            if emo not in emos:
                                emos.append(emo)
                res["characters"].append({
                    "name": f"{char} {lang}", "character": char, "lang": lang,
                    "emotions": sorted(emos), "bytes": total, "files": nfiles,
                    "downloaded": is_downloaded(local_root, lang, char) if local_root else False,
                })
        res["characters"].sort(key=lambda c: (c["lang"], c["character"]))
        res["ok"] = True
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


def _fetch_range(creds, file_id, tmp_path, start, total, name="", progress=None):
    """用 Range 請求把檔案從 start 續傳到 tmp_path（append）。走官方 AuthorizedSession。"""
    from google.auth.transport.requests import AuthorizedSession
    sess = AuthorizedSession(creds)
    url = f"https://www.googleapis.com/drive/v3/files/{file_id}?alt=media"
    headers = {"Range": f"bytes={start}-"} if start else {}
    with sess.get(url, headers=headers, stream=True, timeout=300) as r:
        if r.status_code not in (200, 206):
            raise RuntimeError(f"下載 {name} 失敗 HTTP {r.status_code}")
        # 200＝伺服器不接受 Range（整包重來）→ 覆寫；206＝真的續傳 → 接在後面
        mode = "ab" if (start and r.status_code == 206) else "wb"
        got = start if mode == "ab" else 0
        with open(tmp_path, mode) as fh:
            for chunk in r.iter_content(chunk_size=4 * 1024 * 1024):
                if not chunk:
                    continue
                fh.write(chunk)
                got += len(chunk)
                if progress and total:
                    progress(name, min(100, int(got * 100 / total)))


def download_character(local_root, lang, char, progress=None, creds=None):
    """把角色從 Drive 抓到本機快取 <local_root>/<LANG>/<角色>/（同名同大小跳過）。

    回 {ok, downloaded, skipped, bytes, error}。
    """
    res = {"ok": False, "character": char, "lang": (lang or "").upper(),
           "downloaded": 0, "skipped": 0, "bytes": 0, "error": ""}
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        models_id, _legacy = _models_parent(svc)
        lang_id = _find_folder(svc, res["lang"], models_id) if models_id else None
        char_id = _find_folder(svc, char, lang_id) if lang_id else None
        if not char_id:
            res["error"] = "Drive 上找不到這個角色"; return res
        # 下載目的地要跟掃描端看的是同一層，否則下載完了 is_downloaded 仍判 False
        cache_base = local_models_dir(local_root)

        def _walk(fid, rel):
            for f in _list_children(svc, fid):
                sub = os.path.join(rel, f["name"])
                if f["mimeType"] == FOLDER_MIME:
                    _walk(f["id"], sub); continue
                dst = os.path.join(cache_base, res["lang"], char, sub)
                size = int(f.get("size") or 0)
                if os.path.isfile(dst) and os.path.getsize(dst) == size:
                    res["skipped"] += 1; res["bytes"] += size; continue
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                tmp = dst + ".part"
                # 續傳：慢速網路下大檔常被中斷，沒續傳＝每次從頭來（實測 316MB 約 12 分鐘）
                have = os.path.getsize(tmp) if os.path.isfile(tmp) else 0
                if have > size:                    # .part 比來源還大＝來源換過 → 重下
                    os.remove(tmp); have = 0
                if have < size:
                    _fetch_range(creds, f["id"], tmp, have, size, f["name"], progress)
                # 併發保護：另一個下載可能已經完成並把 .part rename 掉了
                if not os.path.isfile(tmp):
                    if os.path.isfile(dst) and os.path.getsize(dst) == size:
                        res["skipped"] += 1; res["bytes"] += size
                        continue
                    res["error"] = f"{f['name']} 下載中斷"
                    continue
                if os.path.getsize(tmp) != size:   # 大小不符就不當完成，留著 .part 下次續
                    res["error"] = f"{f['name']} 下載不完整"
                    continue
                try:
                    os.replace(tmp, dst)
                except OSError:                     # 別人先 rename 了 → 檔在就算成功
                    if not (os.path.isfile(dst) and os.path.getsize(dst) == size):
                        res["error"] = f"{f['name']} 寫入失敗"
                        continue
                res["downloaded"] += 1; res["bytes"] += size

        _walk(char_id, "")
        # 有任何檔案中斷/不完整就不能算成功——否則 UI 彈「已下載」但 is_downloaded
        # 判 False，畫面永遠停在「未下載」（使用者：我明明下載了）。
        res["ok"] = not res["error"]
        res["complete"] = is_downloaded(local_root, res["lang"], char)
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


def remove_download(local_root, lang, char):
    """移除本機快取（雲端保留）。回 (ok, 釋出bytes)。"""
    import shutil
    cdir = os.path.join(local_models_dir(local_root), (lang or "").upper(), char)
    if not os.path.isdir(cdir):
        return False, 0
    freed = 0
    for r, _d, fs in os.walk(cdir):
        for f in fs:
            try:
                freed += os.path.getsize(os.path.join(r, f))
            except OSError:
                pass
    try:
        shutil.rmtree(cdir)
        return True, freed
    except OSError:
        return False, 0


def delete_character(local_root, lang, char, also_local=True, creds=None):
    """從 Drive 刪掉這個角色（丟垃圾桶，可在 Drive 還原），預設同時清本機快取。

    回 {ok, error}。注意：這會影響所有裝置看到的曲庫。
    """
    res = {"ok": False, "error": ""}
    LANG = (lang or "").upper()
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        models_id, _legacy = _models_parent(svc)
        lang_id = _find_folder(svc, LANG, models_id) if models_id else None
        char_id = _find_folder(svc, char, lang_id) if lang_id else None
        if not char_id:
            res["error"] = "Drive 上找不到這個角色"; return res
        # 丟垃圾桶而非永久刪除 → 使用者可在 Drive 自行還原
        _retry(lambda: svc.files().update(fileId=char_id, body={"trashed": True}).execute())
        if also_local and local_root:
            remove_download(local_root, LANG, char)
        res["ok"] = True
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


# ── 聲波模型（專屬發音）VoiceWeaver/AcousticModels/ ────────────────
#
# 跟語音模型分開放，因為兩者的性質完全不同：
# 語音模型是「可以分享的角色」（幾百 MB，誰拿去都能用），
# 聲波模型是「這個人的聲音特徵」（幾十 KB，換一個人講就完全比不出來）。
# 混在同一層的話，使用者想備份／搬移／刪掉其中一種都會誤傷另一種。
#
# 一個樣板一個 JSON：手機端一次只會改一筆，整包重寫會讓兩台裝置同時編輯時互相蓋掉。

def _acoustic_parent(svc, create=False):
    """VoiceWeaver/AcousticModels 的資料夾 id（找不到且不建時回 None）。

    底下不再分一層帳號 id：這是使用者自己的雲端硬碟，一個 Google 帳號一個，
    而 Firebase 身分也是同一個 Google 帳號換來的——多包一層永遠只有一個子資料夾，
    只是讓他在 Drive 裡多點一次、還看到一串沒有意義的亂碼。
    """
    root_id = _find_folder(svc, ROOT_FOLDER, "root")
    if not root_id:
        if not create:
            return None
        root_id = _ensure_path(svc, [ROOT_FOLDER])
    return (_ensure_folder(svc, ACOUSTIC_DIR, root_id) if create
            else _find_folder(svc, ACOUSTIC_DIR, root_id))


def list_acoustic(creds=None):
    """列出 Drive 上的聲波模型。回 {ok, templates:[dict], error}。"""
    res = {"ok": False, "templates": [], "error": ""}
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        parent = _acoustic_parent(svc)
        if not parent:
            res["ok"] = True; return res          # 還沒有人上傳過
        from google.auth.transport.requests import AuthorizedSession
        sess = AuthorizedSession(creds)
        for f in _list_children(svc, parent):
            if not f["name"].lower().endswith(".json"):
                continue
            r = sess.get(f"https://www.googleapis.com/drive/v3/files/{f['id']}?alt=media",
                         timeout=60)
            if r.status_code != 200:
                continue
            try:
                res["templates"].append(json.loads(r.content.decode("utf-8")))
            except ValueError:
                continue          # 壞掉的單筆不該讓整份清單失敗
        res["ok"] = True
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


def upload_acoustic(template, creds=None):
    """上傳/覆蓋一筆聲波模型（template 需含 id）。回 {ok, error}。"""
    res = {"ok": False, "error": ""}
    tid = str((template or {}).get("id") or "").strip()
    if not tid:
        res["error"] = "樣板缺少 id"; return res
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        parent = _acoustic_parent(svc, create=True)
        from googleapiclient.http import MediaInMemoryUpload
        blob = json.dumps(template, ensure_ascii=False).encode("utf-8")
        media = MediaInMemoryUpload(blob, mimetype="application/json", resumable=False)
        name = f"{tid}.json"
        exist = _find_file(svc, name, parent)
        if exist:
            _retry(lambda: svc.files().update(fileId=exist["id"], media_body=media).execute())
        else:
            _retry(lambda: svc.files().create(
                body={"name": name, "parents": [parent]}, media_body=media,
                fields="id").execute())
        res["ok"] = True
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


def delete_acoustic(template_id, creds=None):
    """刪掉一筆聲波模型（丟垃圾桶，可在 Drive 還原）。回 {ok, error}。"""
    res = {"ok": False, "error": ""}
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        parent = _acoustic_parent(svc)
        f = _find_file(svc, f"{template_id}.json", parent) if parent else None
        if f:
            _retry(lambda: svc.files().update(fileId=f["id"], body={"trashed": True}).execute())
        res["ok"] = True          # 本來就不在＝已經是想要的狀態
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


# ── 離線用：記住上次讀到的雲端曲庫 ─────────────────────
_CACHED_LIST = os.path.join(_APP_DIR, "drive_catalog_cache.json")


def save_cached_catalog(chars):
    try:
        os.makedirs(_APP_DIR, exist_ok=True)
        with open(_CACHED_LIST, "w", encoding="utf-8") as f:
            json.dump({"updatedAt": int(time.time() * 1000), "characters": chars}, f, ensure_ascii=False)
    except OSError:
        pass


def load_cached_catalog(with_age=False):
    """回上次成功讀到的雲端曲庫；沒有回 []。with_age=True 時回 (chars, 秒數)。"""
    try:
        with open(_CACHED_LIST, encoding="utf-8") as f:
            d = json.load(f)
        chars = d.get("characters", [])
        age = max(0.0, time.time() - d.get("updatedAt", 0) / 1000.0)
    except (OSError, ValueError):
        chars, age = [], 1e9
    return (chars, age) if with_age else chars


_refreshing = False


def _mark_downloaded(chars, local_root):
    for c in chars:
        c["downloaded"] = (is_downloaded(local_root, c.get("lang", ""), c.get("character", ""))
                           if local_root else False)
    return chars


def refresh_cloud_catalog_async(local_root=None):
    """背景更新 Drive 曲庫快取（走一次目錄樹實測要 ~30 秒，不能擋在請求路徑上）。"""
    global _refreshing
    if _refreshing:
        return
    local_root = local_root or emotion_root()

    def _work():
        global _refreshing
        try:
            r = list_drive_catalog(local_root)
            if r.get("ok"):
                save_cached_catalog(r["characters"])
        except Exception:  # noqa: BLE001
            pass
        finally:
            _refreshing = False
    _refreshing = True
    import threading
    threading.Thread(target=_work, daemon=True).start()


def cloud_catalog(local_root=None, max_age_s=300, force=False):
    """畫面用的單一入口——**快取優先，絕不讓請求等網路**。

    走一次 Drive 目錄樹實測要 ~31 秒（慢速網路），若每次請求都同步去讀，
    網頁 /voices(4s) /catalog(8s) 必定逾時 → 曲庫永遠顯示空的。
    所以：快取新鮮就直接回；過期就先回舊的、同時背景更新；沒快取才同步讀一次。
    downloaded 一律用本機即時重算（很便宜）。
    """
    local_root = local_root or emotion_root()
    chars, age = load_cached_catalog(with_age=True)
    if chars and not force:
        if age > max_age_s:
            refresh_cloud_catalog_async(local_root)      # 背景更新，這次先回舊的
        return {"ok": True, "offline": False, "stale": age > max_age_s,
                "characters": _mark_downloaded(chars, local_root), "error": ""}
    # 沒快取（或強制）→ 只好同步讀一次
    r = list_drive_catalog(local_root)
    if r.get("ok"):
        save_cached_catalog(r["characters"])
        return {"ok": True, "offline": False, "stale": False,
                "characters": _mark_downloaded(r["characters"], local_root), "error": ""}
    return {"ok": bool(chars), "offline": True, "stale": True,
            "characters": _mark_downloaded(chars, local_root), "error": r.get("error", "")}


def upload_catalog(root, creds=None):
    """把 <root>/catalog.json 上傳到 Drive VoiceWeaver/。"""
    p = os.path.join(root, "catalog.json")
    if not os.path.isfile(p):
        return False
    try:
        creds = creds or get_credentials(interactive=True)
        if not creds:
            return False
        svc = _service(creds)
        leaf = _ensure_path(svc, [ROOT_FOLDER])
        _upload_file(svc, p, leaf)
        return True
    except Exception:
        return False


def upload_library(root, progress=None, log=None):
    """把整個本機曲庫（所有語言/角色）＋ catalog 上傳到使用者 Drive。回總結 dict。"""
    total = {"characters": 0, "uploaded": 0, "skipped": 0, "bytes": 0, "errors": []}
    if not root or not os.path.isdir(root):
        total["errors"].append("找不到曲庫根"); return total
    creds = get_credentials(interactive=True)
    if not creds:
        total["errors"].append("尚未登入 Google"); return total
    for lang in sorted(os.listdir(root)):
        ldir = os.path.join(root, lang)
        if lang.upper() not in LANG_DIRS or not os.path.isdir(ldir):
            continue
        for char in sorted(os.listdir(ldir)):
            if not os.path.isdir(os.path.join(ldir, char)):
                continue
            if log:
                log(f"⬆ {lang.upper()}/{char}")
            r = upload_character(root, lang, char, progress=progress)
            total["characters"] += 1
            total["uploaded"] += r["uploaded"]; total["skipped"] += r["skipped"]
            total["bytes"] += r["bytes"]
            if not r["ok"]:
                total["errors"].append(f"{lang}/{char}: {r['error']}")
    upload_catalog(root, creds=creds)
    return total


if __name__ == "__main__":   # 快速自測（不觸網）：印憑證狀態
    print("deps_ok:", deps_ok())
    print("client_secret:", client_secret_path() or "(未放置)")
    print("signed_in:", is_signed_in())
