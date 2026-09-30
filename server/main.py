from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from starlette.background import BackgroundTask
from pydantic import BaseModel
import html
import http.cookiejar
import ipaddress
import json
import os
import re
import shutil
import socket
import tempfile
import urllib.request
import urllib.parse
from urllib.parse import quote, urlparse
import yt_dlp

app = FastAPI(title="MediaSave Resolver")

class ResolveRequest(BaseModel):
    url: str

ALLOWED = {
    "instagram.com", "www.instagram.com",
    "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
    "x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com"
}

UA = (
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
)

def safe_host(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    suffixes = ["instagram.com", "youtube.com", "x.com", "twitter.com"]
    if host not in ALLOWED and not any(host.endswith("." + d) for d in suffixes):
        raise HTTPException(status_code=400, detail="Unsupported domain")
    return host

def platform_for(host: str) -> str:
    if "instagram" in host:
        return "Instagram"
    if host.endswith("x.com") or "twitter" in host:
        return "X"
    return "YouTube"

def public_base(request: Request) -> str:
    proto = request.headers.get("x-forwarded-proto") or request.url.scheme or "https"
    host = request.headers.get("host") or request.url.netloc
    return f"{proto}://{host}"

def is_safe_remote(url: str) -> bool:
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        return False
    try:
        for item in socket.getaddrinfo(parsed.hostname, None):
            ip = ipaddress.ip_address(item[4][0])
            if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved:
                return False
    except Exception:
        return False
    return True

def scrape_og_media(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            page = response.read().decode("utf-8", errors="ignore")
    except Exception:
        return [], []

    def collect(prop: str):
        patterns = [
            rf'<meta[^>]+property=["\']{prop}(?::secure_url)?["\'][^>]+content=["\']([^"\']+)["\']',
            rf'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']{prop}(?::secure_url)?["\']'
        ]
        found = []
        for pattern in patterns:
            for value in re.findall(pattern, page, flags=re.I):
                value = html.unescape(value)
                if value.startswith("http") and value not in found:
                    found.append(value)
        return found

    return collect("og:video"), collect("og:image")

def instagram_shortcode(url: str):
    match = re.search(r"/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", urlparse(url).path)
    return match.group(1) if match else None

def instagram_graphql_media(url: str):
    shortcode = instagram_shortcode(url)
    if not shortcode:
        return {"items": [], "title": None, "description": None}

    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    headers = {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"}

    try:
        opener.open(urllib.request.Request("https://www.instagram.com/", headers=headers), timeout=15).read(64)
    except Exception:
        pass

    csrf = ""
    for cookie in jar:
        if cookie.name == "csrftoken":
            csrf = cookie.value
            break

    variables = {
        "shortcode": shortcode,
        "__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider": False
    }
    body = urllib.parse.urlencode({
        "doc_id": "27128499623469141",
        "variables": json.dumps(variables, separators=(",", ":"))
    }).encode("utf-8")

    gql_headers = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Content-Type": "application/x-www-form-urlencoded",
        "X-IG-App-ID": "936619743392459",
        "Origin": "https://www.instagram.com",
        "Referer": url,
    }
    if csrf:
        gql_headers["X-CSRFToken"] = csrf

    try:
        req = urllib.request.Request(
            "https://www.instagram.com/graphql/query",
            data=body,
            headers=gql_headers,
            method="POST"
        )
        with opener.open(req, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8", errors="ignore"))
    except Exception:
        return {"items": [], "title": None, "description": None}

    node = (
        payload.get("data", {})
        .get("xdt_api__v1__media__shortcode__web_info", {})
    )
    items = node.get("items") or []
    if not items:
        return {"items": [], "title": None, "description": None}

    root = items[0]
    children = root.get("carousel_media") or [root]
    result = []
    caption_obj = root.get("caption") or {}
    caption_text = (caption_obj.get("text") or "").strip() or None

    for index, media in enumerate(children, start=1):
        video_versions = media.get("video_versions") or []
        if video_versions:
            video_url = video_versions[0].get("url")
            if video_url:
                result.append({
                    "url": video_url,
                    "type": "VIDEO",
                    "ext": "mp4",
                    "index": index
                })
                continue

        candidates = (
            media.get("image_versions2", {}).get("candidates")
            or root.get("image_versions2", {}).get("candidates")
            or []
        )
        if candidates:
            image_url = candidates[0].get("url")
            if image_url:
                result.append({
                    "url": image_url,
                    "type": "IMAGE",
                    "ext": "jpg",
                    "index": index
                })

    return {"items": result, "title": caption_text, "description": caption_text}


def x_status_id(url: str):
    match = re.search(r"/status/(\d+)", urlparse(url).path)
    return match.group(1) if match else None

def fxtwitter_media(url: str):
    status_id = x_status_id(url)
    if not status_id:
        return None

    api_url = f"https://api.fxtwitter.com/2/status/{status_id}"
    req = urllib.request.Request(
        api_url,
        headers={"User-Agent": "MediaSave/1.6 (+https://github.com/asman35/TikTokVideoDownloaderrr)"}
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8", errors="ignore"))
    except Exception:
        return None

    status = payload.get("status") or payload.get("tweet") or {}
    if not isinstance(status, dict):
        return None

    media = status.get("media") or {}
    all_media = media.get("all") or []
    if not all_media:
        all_media = (media.get("photos") or []) + (media.get("videos") or [])

    result = []
    for index, item in enumerate(all_media, start=1):
        item_type = item.get("type")
        if item_type in ("photo", "mosaic_photo"):
            media_url = item.get("url")
            if media_url:
                result.append({
                    "url": media_url,
                    "type": "IMAGE",
                    "ext": "jpg",
                    "index": index
                })
        elif item_type in ("video", "gif"):
            formats = [
                fmt for fmt in (item.get("formats") or [])
                if fmt.get("url") and fmt.get("container") == "mp4"
            ]
            formats.sort(key=lambda fmt: fmt.get("bitrate") or 0, reverse=True)
            media_url = (formats[0].get("url") if formats else None) or item.get("url")
            if media_url:
                result.append({
                    "url": media_url,
                    "type": "VIDEO",
                    "ext": "mp4",
                    "index": index
                })

    text_value = (
        status.get("text")
        or status.get("raw_text")
        or status.get("description")
        or ""
    ).strip() or None

    return {
        "title": text_value,
        "description": text_value,
        "items": result
    }

def yt_options(platform: str, download: bool = False):
    opts = {
        "quiet": True,
        "noplaylist": True,
        "extract_flat": False,
        "http_headers": {"User-Agent": UA},
    }
    if download:
        if platform == "YouTube":
            opts.update({
                "format": "bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[ext=mp4][vcodec^=avc1]/best[ext=mp4]/best",
                "merge_output_format": "mp4",
            })
        else:
            opts.update({
                "format": "bestvideo*+bestaudio/best",
                "merge_output_format": "mp4",
            })
    else:
        opts["skip_download"] = True

    if platform == "YouTube":
        opts["extractor_args"] = {
            "youtube": {
                "player_client": ["web_safari", "ios", "web"]
            }
        }
    return opts

def has_video(info) -> bool:
    entries = info.get("entries") or [info]
    for entry in entries:
        if not entry:
            continue
        if entry.get("vcodec") not in (None, "none"):
            return True
        for fmt in entry.get("formats") or []:
            if fmt.get("url") and fmt.get("vcodec") not in (None, "none"):
                return True
    return False

def safe_name(value: str, fallback: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", value or "")
    cleaned = cleaned.strip("._")[:100]
    return cleaned or fallback

def build_asset_url(request: Request, media_url: str, referer: str, name: str) -> str:
    return (
        f"{public_base(request)}/asset?url={quote(media_url, safe='')}"
        f"&referer={quote(referer, safe='')}&name={quote(name, safe='')}"
    )

@app.get("/health")
def health():
    return {"ok": True}

@app.get("/asset")
def asset(url: str, referer: str = "", name: str = "media"):
    if not is_safe_remote(url):
        raise HTTPException(status_code=400, detail="Invalid media URL")

    headers = {"User-Agent": UA}
    if referer:
        headers["Referer"] = referer

    try:
        remote = urllib.request.urlopen(
            urllib.request.Request(url, headers=headers),
            timeout=30
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Medya kaynağına ulaşılamadı.") from exc

    content_type = remote.headers.get("Content-Type") or "application/octet-stream"
    disposition_name = safe_name(name, "mediasave_media")

    def stream():
        try:
            while True:
                chunk = remote.read(1024 * 256)
                if not chunk:
                    break
                yield chunk
        finally:
            remote.close()

    return StreamingResponse(
        stream(),
        media_type=content_type,
        headers={"Content-Disposition": f'attachment; filename="{disposition_name}"'}
    )

@app.get("/download-video")
def download_video(url: str, name: str = "mediasave_video.mp4"):
    host = safe_host(url)
    platform = platform_for(host)
    temp_dir = tempfile.mkdtemp(prefix="mediasave_")
    output_template = os.path.join(temp_dir, "media.%(ext)s")

    opts = yt_options(platform, download=True)
    opts["outtmpl"] = output_template

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.extract_info(url, download=True)

        files = [
            os.path.join(temp_dir, f)
            for f in os.listdir(temp_dir)
            if os.path.isfile(os.path.join(temp_dir, f)) and not f.endswith((".part", ".ytdl"))
        ]
        if not files:
            raise RuntimeError("No downloaded media")

        media_path = max(files, key=os.path.getsize)
        final_name = safe_name(name, f"{platform.lower()}_video.mp4")
        if "." not in final_name:
            final_name += ".mp4"

        return FileResponse(
            media_path,
            media_type="video/mp4",
            filename=final_name,
            background=BackgroundTask(shutil.rmtree, temp_dir, ignore_errors=True)
        )
    except Exception as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        if platform == "YouTube":
            raise HTTPException(
                status_code=400,
                detail="YouTube bu videonun medya akışına sunucu erişimini engelledi. Başka bir herkese açık video deneyin."
            ) from exc
        raise HTTPException(status_code=400, detail=f"{platform} videosu indirilemedi.") from exc


@app.post("/youtube-meta")
def youtube_meta(req: ResolveRequest):
    host = safe_host(req.url)
    if platform_for(host) != "YouTube":
        raise HTTPException(status_code=400, detail="Bu uç nokta yalnızca YouTube içindir.")

    try:
        with yt_dlp.YoutubeDL(yt_options("YouTube", download=False)) as ydl:
            info = ydl.extract_info(req.url, download=False)
        return {
            "platform": "YouTube",
            "title": (info.get("title") or "").strip() or "YouTube Video",
            "description": (info.get("description") or "").strip() or None
        }
    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail="YouTube video bilgileri alınamadı."
        ) from exc

@app.post("/resolve")
def resolve(req: ResolveRequest, request: Request):
    host = safe_host(req.url)
    platform = platform_for(host)

    if platform == "Instagram":
        ig_data = instagram_graphql_media(req.url)
        if ig_data and ig_data.get("items"):
            title = ig_data.get("title") or f"Instagram {instagram_shortcode(req.url) or 'media'}"
            items = []
            for media in ig_data["items"]:
                base = safe_name(title, f"instagram_{instagram_shortcode(req.url) or 'media'}")
                suffix = f"_{media['index']}" if len(ig_data["items"]) > 1 else ""
                file_name = f"{base}{suffix}.{media['ext']}"
                items.append({
                    "downloadUrl": build_asset_url(request, media["url"], req.url, file_name),
                    "fileName": file_name,
                    "type": media["type"],
                    "title": title,
                    "description": ig_data.get("description")
                })
            return {
                "platform": platform,
                "title": title,
                "description": ig_data.get("description"),
                "items": items
            }

    if platform == "X":
        x_data = fxtwitter_media(req.url)
        if x_data and x_data.get("items"):
            title = x_data.get("title") or f"X {x_status_id(req.url) or 'media'}"
            items = []
            for media in x_data["items"]:
                base = safe_name(title, f"x_{x_status_id(req.url) or 'media'}")
                suffix = f"_{media['index']}" if len(x_data["items"]) > 1 else ""
                file_name = f"{base}{suffix}.{media['ext']}"
                items.append({
                    "downloadUrl": build_asset_url(request, media["url"], req.url, file_name),
                    "fileName": file_name,
                    "type": media["type"],
                    "title": title,
                    "description": x_data.get("description")
                })
            return {
                "platform": platform,
                "title": title,
                "description": x_data.get("description"),
                "items": items
            }

    info = None
    extract_error = None
    try:
        with yt_dlp.YoutubeDL(yt_options(platform, download=False)) as ydl:
            info = ydl.extract_info(req.url, download=False)
    except Exception as exc:
        extract_error = exc

    if info and has_video(info):
        media_id = info.get("id") or "video"
        title = (info.get("title") or "").strip() or f"{platform} {media_id}"
        description = (info.get("description") or "").strip() or None
        file_name = f"{safe_name(title, platform.lower() + '_' + media_id)}.mp4"

        direct = (
            f"{public_base(request)}/download-video?url={quote(req.url, safe='')}"
            f"&name={quote(file_name, safe='')}"
        )

        if platform == "YouTube":
            muxed = []
            for fmt in info.get("formats") or []:
                if (
                    fmt.get("url")
                    and fmt.get("ext") == "mp4"
                    and str(fmt.get("vcodec") or "").startswith("avc1")
                    and str(fmt.get("acodec") or "").startswith("mp4a")
                ):
                    muxed.append(fmt)
            if muxed:
                muxed.sort(
                    key=lambda f: (
                        f.get("height") or 0,
                        f.get("tbr") or 0
                    ),
                    reverse=True
                )
                chosen = muxed[0]
                direct = build_asset_url(request, chosen["url"], req.url, file_name)

        return {
            "platform": platform,
            "title": title,
            "description": description,
            "items": [{
                "downloadUrl": direct,
                "fileName": file_name,
                "type": "VIDEO",
                "title": title,
                "description": description
            }]
        }

    if platform == "YouTube":
        raise HTTPException(
            status_code=400,
            detail="Bu YouTube videosunda gerçek MP4 akışı alınamadı. Sahte/bozuk 3 saniyelik dosya indirmek yerine indirme durduruldu."
        )

    og_videos, og_images = scrape_og_media(req.url)

    if og_videos:
        items = []
        for index, media_url in enumerate(og_videos, start=1):
            file_name = f"{platform.lower()}_video_{index}.mp4"
            items.append({
                "downloadUrl": build_asset_url(request, media_url, req.url, file_name),
                "fileName": file_name,
                "type": "VIDEO"
            })
        return {"platform": platform, "title": info.get("title") if info else None, "items": items}

    if og_images:
        items = []
        for index, media_url in enumerate(og_images, start=1):
            file_name = f"{platform.lower()}_image_{index}.jpg"
            items.append({
                "downloadUrl": build_asset_url(request, media_url, req.url, file_name),
                "fileName": file_name,
                "type": "IMAGE"
            })
        return {"platform": platform, "title": info.get("title") if info else None, "items": items}

    if platform == "YouTube" and extract_error:
        raise HTTPException(
            status_code=400,
            detail="YouTube bu video için sunucu doğrulaması istedi. Başka bir video deneyin."
        ) from extract_error

    raise HTTPException(status_code=400, detail=f"{platform} içeriği çözümlenemedi.")
