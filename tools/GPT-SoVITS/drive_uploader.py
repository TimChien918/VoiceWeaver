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


def parse_ref_filename(fname):
    """『【情緒】逐字稿.wav』→ (canon情緒, 逐字稿)。沿用 emotion_models 同一套解析。"""
    try:
        import sys
        if _here() not in sys.path:
            sys.path.insert(0, _here())
        import emotion_models
        return emotion_models.parse_ref_filename(fname)
    except Exception:
        return "中立", os.path.splitext(fname)[0]


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
    """把本機 <root>/<LANG>/<角色>/ 整包上傳到 Drive VoiceWeaver/<LANG>/<角色>/。

    回 {ok, character, lang, uploaded, skipped, bytes, error}。
    """
    res = {"ok": False, "character": char, "lang": (lang or "").upper(),
           "uploaded": 0, "skipped": 0, "bytes": 0, "error": ""}
    cdir = os.path.join(root, res["lang"], char)
    if not os.path.isdir(cdir):
        res["error"] = "找不到角色資料夾"; return res
    try:
        creds = get_credentials(interactive=True)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        leaf = _ensure_path(svc, [ROOT_FOLDER, res["lang"], char])
        for r, _d, files in os.walk(cdir):
            rel = os.path.relpath(r, cdir)
            parent = leaf if rel == "." else _ensure_path(
                svc, [ROOT_FOLDER, res["lang"], char] + rel.split(os.sep))
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
    cdir = os.path.join(local_root, (lang or "").upper(), char)
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
        root_id = _find_folder(svc, ROOT_FOLDER, "root")
        if not root_id:
            res["ok"] = True          # 已登入但 Drive 上還沒有曲庫（還沒上傳過）
            return res
        for lf in _list_children(svc, root_id, folders_only=True):
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


def download_character(local_root, lang, char, progress=None, creds=None):
    """把角色從 Drive 抓到本機快取 <local_root>/<LANG>/<角色>/（同名同大小跳過）。

    回 {ok, downloaded, skipped, bytes, error}。
    """
    from googleapiclient.http import MediaIoBaseDownload
    import io
    res = {"ok": False, "character": char, "lang": (lang or "").upper(),
           "downloaded": 0, "skipped": 0, "bytes": 0, "error": ""}
    try:
        creds = creds or get_credentials(interactive=False)
        if not creds:
            res["error"] = "尚未登入 Google"; return res
        svc = _service(creds)
        root_id = _find_folder(svc, ROOT_FOLDER, "root")
        lang_id = _find_folder(svc, res["lang"], root_id) if root_id else None
        char_id = _find_folder(svc, char, lang_id) if lang_id else None
        if not char_id:
            res["error"] = "Drive 上找不到這個角色"; return res

        def _walk(fid, rel):
            for f in _list_children(svc, fid):
                sub = os.path.join(rel, f["name"])
                if f["mimeType"] == FOLDER_MIME:
                    _walk(f["id"], sub); continue
                dst = os.path.join(local_root, res["lang"], char, sub)
                size = int(f.get("size") or 0)
                if os.path.isfile(dst) and os.path.getsize(dst) == size:
                    res["skipped"] += 1; res["bytes"] += size; continue
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                req = svc.files().get_media(fileId=f["id"])
                tmp = dst + ".part"
                with io.FileIO(tmp, "wb") as fh:
                    dl = MediaIoBaseDownload(fh, req, chunksize=8 * 1024 * 1024)
                    done = False
                    while not done:
                        status, done = _retry(lambda: dl.next_chunk())
                        if status and progress:
                            progress(f["name"], int(status.progress() * 100))
                os.replace(tmp, dst)
                res["downloaded"] += 1; res["bytes"] += size

        _walk(char_id, "")
        res["ok"] = True
    except Exception as e:  # noqa: BLE001
        res["error"] = str(e)
    return res


def remove_download(local_root, lang, char):
    """移除本機快取（雲端保留）。回 (ok, 釋出bytes)。"""
    import shutil
    cdir = os.path.join(local_root, (lang or "").upper(), char)
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
        root_id = _find_folder(svc, ROOT_FOLDER, "root")
        lang_id = _find_folder(svc, LANG, root_id) if root_id else None
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


# ── 離線用：記住上次讀到的雲端曲庫 ─────────────────────
_CACHED_LIST = os.path.join(_APP_DIR, "drive_catalog_cache.json")


def save_cached_catalog(chars):
    try:
        os.makedirs(_APP_DIR, exist_ok=True)
        with open(_CACHED_LIST, "w", encoding="utf-8") as f:
            json.dump({"updatedAt": int(time.time() * 1000), "characters": chars}, f, ensure_ascii=False)
    except OSError:
        pass


def load_cached_catalog():
    """回上次成功讀到的雲端曲庫（離線時用）；沒有回 []。"""
    try:
        with open(_CACHED_LIST, encoding="utf-8") as f:
            return json.load(f).get("characters", [])
    except (OSError, ValueError):
        return []


def cloud_catalog(local_root=None, allow_offline=True):
    """畫面用的單一入口：連得上就讀 Drive（並存快取）；連不上就回上次的清單。

    離線時 downloaded 旗標會即時用本機重算 → 只有已下載的能用（決策 3a）。
    回 {ok, offline, characters[], error}
    """
    local_root = local_root or emotion_root()
    r = list_drive_catalog(local_root)
    if r.get("ok"):
        save_cached_catalog(r["characters"])
        return {"ok": True, "offline": False, "characters": r["characters"], "error": ""}
    if not allow_offline:
        return {"ok": False, "offline": True, "characters": [], "error": r.get("error", "")}
    chars = load_cached_catalog()
    for c in chars:      # 離線也要正確標示哪些是現成可用的
        c["downloaded"] = is_downloaded(local_root, c.get("lang", ""), c.get("character", "")) if local_root else False
    return {"ok": bool(chars), "offline": True, "characters": chars, "error": r.get("error", "")}


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
