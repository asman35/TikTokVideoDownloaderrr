from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import html
import re
import urllib.request
from urllib.parse import urlparse
import yt_dlp

app = FastAPI(title="MediaSave Resolver")

class ResolveRequest(BaseModel):
    url: str

ALLOWED = {
    "instagram.com", "www.instagram.com",
    "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
    "x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com"
}

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

def media_type_from_url(url: str) -> str:
    clean = url.lower().split("?")[0]
    if clean.endswith((".jpg", ".jpeg", ".png", ".webp")):
        return "IMAGE"
    return "VIDEO"

def scrape_og_images(url: str):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            page = response.read().decode("utf-8", errors="ignore")
    except Exception:
        return []

    patterns = [
        r'<meta[^>]+property=["\']og:image(?::secure_url)?["\'][^>]+content=["\']([^"\']+)["\']',
        r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:image(?::secure_url)?["\']'
    ]
    found = []
    for pattern in patterns:
        for value in re.findall(pattern, page, flags=re.I):
            value = html.unescape(value)
            if value.startswith("http") and value not in found:
                found.append(value)
    return found

def yt_options(platform: str):
    opts = {
        "quiet": True,
        "skip_download": True,
        "noplaylist": True,
        "format": "best[ext=mp4]/best",
        "extract_flat": False,
    }
    if platform == "YouTube":
        opts["extractor_args"] = {
            "youtube": {
                "player_client": ["android_vr", "web_safari", "ios"]
            }
        }
    return opts

@app.get("/health")
def health():
    return {"ok": True}

@app.post("/resolve")
def resolve(req: ResolveRequest):
    host = safe_host(req.url)
    platform = platform_for(host)

    try:
        with yt_dlp.YoutubeDL(yt_options(platform)) as ydl:
            info = ydl.extract_info(req.url, download=False)

        items = []
        entries = info.get("entries") or [info]
        for index, entry in enumerate(entries, start=1):
            if not entry:
                continue

            direct = entry.get("url")
            if direct:
                item_type = media_type_from_url(direct)
                ext = "jpg" if item_type == "IMAGE" else (entry.get("ext") or "mp4")
                items.append({
                    "downloadUrl": direct,
                    "fileName": f"{platform.lower()}_{entry.get('id','media')}_{index}.{ext}",
                    "type": item_type
                })

            requested = entry.get("requested_downloads") or []
            for r_index, media in enumerate(requested, start=1):
                direct2 = media.get("url")
                if not direct2:
                    continue
                item_type = media_type_from_url(direct2)
                ext = "jpg" if item_type == "IMAGE" else (media.get("ext") or "mp4")
                candidate = {
                    "downloadUrl": direct2,
                    "fileName": f"{platform.lower()}_{entry.get('id','media')}_{index}_{r_index}.{ext}",
                    "type": item_type
                }
                if candidate["downloadUrl"] not in [x["downloadUrl"] for x in items]:
                    items.append(candidate)

        if items:
            return {
                "platform": platform,
                "title": info.get("title"),
                "items": items
            }
    except Exception as exc:
        if platform == "YouTube":
            raise HTTPException(
                status_code=400,
                detail="YouTube bu sunucu IP'sinden doğrulama istiyor. Başka bir video deneyin."
            ) from exc

    images = scrape_og_images(req.url)
    if images:
        return {
            "platform": platform,
            "title": None,
            "items": [
                {
                    "downloadUrl": image_url,
                    "fileName": f"{platform.lower()}_image_{index}.jpg",
                    "type": "IMAGE"
                }
                for index, image_url in enumerate(images, start=1)
            ]
        }

    raise HTTPException(status_code=400, detail=f"{platform} içeriği çözümlenemedi.")
