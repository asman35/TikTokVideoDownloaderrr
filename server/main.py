from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from starlette.background import BackgroundTask
from pydantic import BaseModel
import html
import ipaddress
import os
import re
import shutil
import socket
import tempfile
import urllib.request
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

def yt_options(platform: str, download: bool = False):
    opts = {
        "quiet": True,
        "noplaylist": True,
        "extract_flat": False,
        "http_headers": {"User-Agent": UA},
    }
    if download:
        opts.update({
            "format": "bestvideo*+bestaudio/best",
            "merge_output_format": "mp4",
        })
    else:
        opts["skip_download"] = True

    if platform == "YouTube":
        opts["extractor_args"] = {
            "youtube": {
                "player_client": ["android_vr", "web_safari", "ios", "tv_embedded"]
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
                detail="YouTube bu video için sunucu doğrulaması istedi veya medya alınamadı."
            ) from exc
        raise HTTPException(status_code=400, detail=f"{platform} videosu indirilemedi.") from exc

@app.post("/resolve")
def resolve(req: ResolveRequest, request: Request):
    host = safe_host(req.url)
    platform = platform_for(host)

    info = None
    extract_error = None
    try:
        with yt_dlp.YoutubeDL(yt_options(platform, download=False)) as ydl:
            info = ydl.extract_info(req.url, download=False)
    except Exception as exc:
        extract_error = exc

    if info and has_video(info):
        media_id = info.get("id") or "video"
        file_name = f"{platform.lower()}_{media_id}.mp4"
        direct = (
            f"{public_base(request)}/download-video?url={quote(req.url, safe='')}"
            f"&name={quote(file_name, safe='')}"
        )
        return {
            "platform": platform,
            "title": info.get("title"),
            "items": [{
                "downloadUrl": direct,
                "fileName": file_name,
                "type": "VIDEO"
            }]
        }

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
