from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, HttpUrl
import yt_dlp
from urllib.parse import urlparse

app = FastAPI(title="MediaSave Resolver")

class ResolveRequest(BaseModel):
    url: str

ALLOWED = {
    "instagram.com", "www.instagram.com",
    "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"
}

def safe_host(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    if host not in ALLOWED and not any(host.endswith("." + d) for d in ["instagram.com", "youtube.com"]):
        raise HTTPException(status_code=400, detail="Unsupported domain")
    return host

@app.get("/health")
def health():
    return {"ok": True}

@app.post("/resolve")
def resolve(req: ResolveRequest):
    host = safe_host(req.url)
    platform = "Instagram" if "instagram" in host else "YouTube"

    opts = {
        "quiet": True,
        "skip_download": True,
        "noplaylist": True,
        "format": "best[ext=mp4]/best",
        "extract_flat": False,
    }

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(req.url, download=False)
    except Exception as e:
        raise HTTPException(status_code=400, detail="Media could not be resolved") from e

    items = []
    entries = info.get("entries") or [info]
    for entry in entries:
        if not entry:
            continue
        direct = entry.get("url")
        if direct:
            items.append({
                "downloadUrl": direct,
                "fileName": f"{platform.lower()}_{entry.get('id','media')}.mp4",
                "type": "VIDEO"
            })

    if not items:
        raise HTTPException(status_code=404, detail="No downloadable media found")

    return {
        "platform": platform,
        "title": info.get("title"),
        "items": items
    }
