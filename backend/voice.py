import asyncio
import os

from fastapi import APIRouter, File, HTTPException, UploadFile
from google import genai
from google.genai import types

router = APIRouter()


@router.post("/api/transcribe")
async def transcribe(audio: UploadFile = File(...)):
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="GEMINI_API_KEY is not configured")
    data = await audio.read()
    try:
        client = genai.Client(api_key=api_key)
        result = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-2.5-flash",
            contents=[
                "Transcribe this audio verbatim. Return only the transcription.",
                types.Part.from_bytes(data=data, mime_type=audio.content_type or "audio/webm"),
            ],
        )
        return {"text": result.text or ""}
    except Exception as exc:
        raise HTTPException(status_code=502, detail="transcription failed") from exc
