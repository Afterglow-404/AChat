"""Minimal HTTP adapter for Qwen3-TTS CustomVoice.

This process owns the Qwen3-TTS model. Wisp talks to it through three small
endpoints: /healthz, /speakers, and /tts. The model is loaded lazily on the
first synthesis request so that the server can be used for health checks and
configuration before a GPU model is available.
"""

from __future__ import annotations

import io
import os
import threading
from typing import Any

import numpy as np
import soundfile as sf
import torch
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field


MODEL_ID = os.getenv(
    "QWEN3_TTS_MODEL",
    "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice",
)
DEVICE = os.getenv("QWEN3_TTS_DEVICE", "cuda:0" if torch.cuda.is_available() else "cpu")
DTYPE_NAME = os.getenv("QWEN3_TTS_DTYPE", "float16" if DEVICE.startswith("cuda") else "float32")
HOST = os.getenv("QWEN3_TTS_HOST", "0.0.0.0")
PORT = int(os.getenv("QWEN3_TTS_PORT", "8000"))
DEFAULT_SPEAKER = os.getenv("QWEN3_TTS_SPEAKER", "Vivian")
DEFAULT_LANGUAGE = os.getenv("QWEN3_TTS_LANGUAGE", "Chinese")
LOAD_ON_START = os.getenv("QWEN3_TTS_LOAD_ON_START", "0").lower() in {"1", "true", "yes"}
DO_SAMPLE = os.getenv("QWEN3_TTS_DO_SAMPLE", "0").lower() in {"1", "true", "yes"}
SUBTALKER_DO_SAMPLE = os.getenv("QWEN3_TTS_SUBTALKER_DOSAMPLE", "0").lower() in {"1", "true", "yes"}
MAX_NEW_TOKENS = int(os.getenv("QWEN3_TTS_MAX_NEW_TOKENS", "2048"))

# CustomVoice speakers documented by the Qwen3-TTS model family. Override
# this list when using a model revision with a different speaker inventory.
DEFAULT_SPEAKERS = [
    item.strip()
    for item in os.getenv(
        "QWEN3_TTS_SPEAKERS",
        "Vivian,Serena,Uncle_Fu,Dylan,Eric,Ryan,Aiden,Ono_Anna,Sohee",
    ).split(",")
    if item.strip()
]

app = FastAPI(title="Wisp Qwen3-TTS Adapter", version="0.1.0")
_model_lock = threading.Lock()
_inference_lock = threading.Lock()
_model: Any = None
_model_error: str | None = None


class TtsRequest(BaseModel):
    text: str = Field(min_length=1)
    language: str = DEFAULT_LANGUAGE
    speaker: str = DEFAULT_SPEAKER
    instruct: str = ""
    instruction: str = ""
    response_format: str = "wav"


def _dtype() -> torch.dtype:
    values = {
        "float16": torch.float16,
        "fp16": torch.float16,
        "bfloat16": torch.bfloat16,
        "bf16": torch.bfloat16,
        "float32": torch.float32,
        "fp32": torch.float32,
    }
    value = values.get(DTYPE_NAME.lower())
    if value is None:
        raise RuntimeError(f"Unsupported QWEN3_TTS_DTYPE: {DTYPE_NAME}")
    if DEVICE == "cpu" and value == torch.bfloat16:
        return torch.float32
    return value


def _load_model() -> Any:
    """Load exactly once and retain the error for /healthz diagnostics."""
    global _model, _model_error
    if _model is not None:
        return _model
    with _model_lock:
        if _model is not None:
            return _model
        try:
            from qwen_tts import Qwen3TTSModel

            kwargs: dict[str, Any] = {
                "device_map": DEVICE,
                "dtype": _dtype(),
            }
            _model = Qwen3TTSModel.from_pretrained(MODEL_ID, **kwargs)
            _model_error = None
            return _model
        except Exception as exc:  # keep the server alive for diagnostics
            _model_error = f"{type(exc).__name__}: {exc}"
            raise


def _language(value: str) -> str:
    aliases = {
        "zh": "Chinese",
        "en": "English",
        "ja": "Japanese",
        "ko": "Korean",
        "de": "German",
        "fr": "French",
        "ru": "Russian",
        "pt": "Portuguese",
        "es": "Spanish",
        "it": "Italian",
        "auto": "Auto",
    }
    normalized = value.strip()
    return aliases.get(normalized.lower(), normalized or DEFAULT_LANGUAGE)


def _wav_bytes(wavs: Any, sample_rate: int) -> bytes:
    """Convert the first model waveform to a standard PCM WAV response."""
    waveform = wavs[0] if isinstance(wavs, (list, tuple)) else wavs
    if hasattr(waveform, "detach"):
        waveform = waveform.detach().float().cpu().numpy()
    waveform = np.asarray(waveform)
    waveform = np.squeeze(waveform)
    if waveform.ndim != 1:
        raise RuntimeError(f"Unexpected waveform shape: {waveform.shape}")
    buffer = io.BytesIO()
    sf.write(buffer, waveform, sample_rate, format="WAV", subtype="PCM_16")
    return buffer.getvalue()


@app.get("/healthz")
def healthz() -> JSONResponse:
    return JSONResponse(
        {
            # Wisp uses the HTTP status to decide whether it should attempt
            # synthesis. Keep the adapter reachable while the model is lazy.
            "ok": True,
            "ready": _model is not None,
            "model": MODEL_ID,
            "device": DEVICE,
            "model_error": _model_error,
        },
        status_code=200,
    )


@app.get("/speakers")
def speakers() -> list[str]:
    return DEFAULT_SPEAKERS


@app.post("/tts")
def tts(request: TtsRequest) -> Response:
    if request.response_format.lower() not in {"wav", "wave"}:
        raise HTTPException(status_code=400, detail="Only wav response_format is supported")
    speaker = request.speaker.strip() or DEFAULT_SPEAKER
    if DEFAULT_SPEAKERS and speaker not in DEFAULT_SPEAKERS:
        raise HTTPException(status_code=400, detail=f"Unknown speaker: {speaker}")
    instruction = request.instruct.strip() or request.instruction.strip()
    try:
        model = _load_model()
        # Qwen3-TTS inference is not assumed to be thread-safe. Serializing
        # requests also prevents concurrent GPU allocations on small cards.
        with _inference_lock:
            generation = {
                "do_sample": DO_SAMPLE,
                "subtalker_dosample": SUBTALKER_DO_SAMPLE,
                "max_new_tokens": MAX_NEW_TOKENS,
            }
            wavs, sample_rate = model.generate_custom_voice(
                text=request.text,
                language=_language(request.language),
                speaker=speaker,
                instruct=instruction if "0.6B" not in MODEL_ID else "",
                **generation,
            )
        return Response(_wav_bytes(wavs, int(sample_rate)), media_type="audio/wav")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Qwen3-TTS inference failed: {exc}") from exc


@app.on_event("startup")
def startup() -> None:
    if LOAD_ON_START:
        try:
            _load_model()
        except Exception:
            # /healthz exposes the retained error; startup should still leave
            # the HTTP service available for diagnostics.
            pass


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=HOST, port=PORT)
