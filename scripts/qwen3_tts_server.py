"""HTTP adapter for the official Qwen3-TTS generation modes.

This process owns the Qwen3-TTS model. Wisp talks to it through /healthz,
/speakers, /capabilities, /tts, and /clone. Model weights are loaded lazily so
the adapter remains useful for health checks before a GPU model is available.
"""

from __future__ import annotations

import io
import os
import threading
from pathlib import Path
from typing import Any

import numpy as np
import soundfile as sf
import torch
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field


MODEL_ID = os.getenv(
    "QWEN3_TTS_MODEL",
    "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice",
)
MODEL_MODE = os.getenv("QWEN3_TTS_MODE", "").strip().lower()
DEVICE = os.getenv("QWEN3_TTS_DEVICE", "cuda:0" if torch.cuda.is_available() else "cpu")
DTYPE_NAME = os.getenv("QWEN3_TTS_DTYPE", "float16" if DEVICE.startswith("cuda") else "float32")
HOST = os.getenv("QWEN3_TTS_HOST", "0.0.0.0")
PORT = int(os.getenv("QWEN3_TTS_PORT", "8000"))
DEFAULT_SPEAKER = os.getenv("QWEN3_TTS_SPEAKER", "Vivian")
DEFAULT_LANGUAGE = os.getenv("QWEN3_TTS_LANGUAGE", "Chinese")
DEFAULT_REF_AUDIO = os.getenv("QWEN3_TTS_REF_AUDIO", "").strip()
DEFAULT_REF_TEXT = os.getenv("QWEN3_TTS_REF_TEXT", "").strip()
DEFAULT_X_VECTOR_ONLY = os.getenv("QWEN3_TTS_X_VECTOR_ONLY", "0").lower() in {"1", "true", "yes"}
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
_clone_prompt_cache: dict[tuple[str, str, bool], Any] = {}


class TtsRequest(BaseModel):
    text: str = Field(min_length=1)
    language: str = DEFAULT_LANGUAGE
    speaker: str = DEFAULT_SPEAKER
    instruct: str = ""
    instruction: str = ""
    mode: str = ""
    ref_audio: str = ""
    ref_text: str = ""
    x_vector_only_mode: bool = DEFAULT_X_VECTOR_ONLY
    response_format: str = "wav"


def _mode(value: str = "") -> str:
    """Resolve the explicit mode first, then infer it from the official ID."""
    normalized = (value or MODEL_MODE).strip().lower().replace("-", "_")
    if normalized in {"base", "clone", "voice_clone", "voiceclone"}:
        return "base"
    if normalized in {"voice_design", "voicedesign", "design"}:
        return "voice_design"
    if "voicedesign" in MODEL_ID.lower():
        return "voice_design"
    if "-base" in MODEL_ID.lower() or MODEL_ID.lower().endswith("base"):
        return "base"
    return "custom_voice"


def _supported_languages(model: Any = None) -> list[str]:
    if model is not None and hasattr(model, "get_supported_languages"):
        try:
            return list(model.get_supported_languages())
        except Exception:
            pass
    return ["Chinese", "English", "Japanese", "Korean", "German", "French", "Russian", "Portuguese", "Spanish", "Italian"]


def _supported_speakers(model: Any = None) -> list[str]:
    if _mode() != "custom_voice":
        return []
    if model is not None and hasattr(model, "get_supported_speakers"):
        try:
            return list(model.get_supported_speakers())
        except Exception:
            pass
    return DEFAULT_SPEAKERS


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


def _validate_reference(ref_audio: str) -> str:
    value = (ref_audio or DEFAULT_REF_AUDIO).strip()
    if not value:
        raise HTTPException(status_code=400, detail="ref_audio is required for Qwen3-TTS Base voice clone")
    # Local paths are useful for the desktop bridge. URLs and data URLs are
    # passed through because the official qwen-tts package accepts both.
    if not value.startswith(("http://", "https://", "data:")) and not Path(value).is_file():
        raise HTTPException(status_code=400, detail=f"Reference audio file not found: {value}")
    return value


def _clone_prompt(ref_audio: Any, ref_text: str, x_vector_only_mode: bool) -> Any:
    """Build and reuse the official clone prompt when a path-like input is used."""
    model = _load_model()
    text = (ref_text or DEFAULT_REF_TEXT).strip()
    if not x_vector_only_mode and not text:
        raise HTTPException(status_code=400, detail="ref_text is required unless x_vector_only_mode is enabled")
    if isinstance(ref_audio, str):
        key = (ref_audio, text, bool(x_vector_only_mode))
        if key not in _clone_prompt_cache:
            _clone_prompt_cache[key] = model.create_voice_clone_prompt(
                ref_audio=ref_audio,
                ref_text=text,
                x_vector_only_mode=x_vector_only_mode,
            )
        return _clone_prompt_cache[key]
    return model.create_voice_clone_prompt(
        ref_audio=ref_audio,
        ref_text=text,
        x_vector_only_mode=x_vector_only_mode,
    )


def _generate(request: TtsRequest, ref_audio: Any = None, ref_text: str = "") -> tuple[Any, int]:
    model = _load_model()
    mode = _mode(request.mode)
    language = _language(request.language)
    instruction = request.instruct.strip() or request.instruction.strip()
    generation = {
        "do_sample": DO_SAMPLE,
        "subtalker_dosample": SUBTALKER_DO_SAMPLE,
        "max_new_tokens": MAX_NEW_TOKENS,
    }
    if mode == "base":
        reference = ref_audio if ref_audio is not None else _validate_reference(request.ref_audio)
        prompt = _clone_prompt(reference, ref_text or request.ref_text, request.x_vector_only_mode)
        return model.generate_voice_clone(
            text=request.text,
            language=language,
            voice_clone_prompt=prompt,
            **generation,
        )
    if mode == "voice_design":
        return model.generate_voice_design(
            text=request.text,
            language=language,
            instruct=instruction,
            **generation,
        )
    speaker = request.speaker.strip() or DEFAULT_SPEAKER
    speakers = _supported_speakers(model)
    if speakers and speaker not in speakers:
        raise HTTPException(status_code=400, detail=f"Unknown speaker: {speaker}")
    return model.generate_custom_voice(
        text=request.text,
        language=language,
        speaker=speaker,
        instruct=instruction if "0.6B" not in MODEL_ID else "",
        **generation,
    )


@app.get("/healthz")
def healthz() -> JSONResponse:
    return JSONResponse(
        {
            # Wisp uses the HTTP status to decide whether it should attempt
            # synthesis. Keep the adapter reachable while the model is lazy.
            "ok": True,
            "ready": _model is not None,
            "model": MODEL_ID,
            "mode": _mode(),
            "device": DEVICE,
            "supports": {
                "custom_voice": _mode() == "custom_voice",
                "voice_clone": _mode() == "base",
                "voice_design": _mode() == "voice_design",
                "upload_clone": _mode() == "base",
            },
            "model_error": _model_error,
        },
        status_code=200,
    )


@app.get("/speakers")
def speakers() -> list[str]:
    return _supported_speakers(_model)


@app.get("/capabilities")
def capabilities() -> JSONResponse:
    return JSONResponse({
        "model": MODEL_ID,
        "mode": _mode(),
        "speakers": _supported_speakers(_model),
        "languages": _supported_languages(_model),
        "supports_voice_clone": _mode() == "base",
        "supports_voice_design": _mode() == "voice_design",
    })


@app.post("/tts")
def tts(request: TtsRequest) -> Response:
    if request.response_format.lower() not in {"wav", "wave"}:
        raise HTTPException(status_code=400, detail="Only wav response_format is supported")
    try:
        # Qwen3-TTS inference is not assumed to be thread-safe. Serializing
        # requests also prevents concurrent GPU allocations on small cards.
        with _inference_lock:
            wavs, sample_rate = _generate(request)
        return Response(_wav_bytes(wavs, int(sample_rate)), media_type="audio/wav")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Qwen3-TTS inference failed: {exc}") from exc


@app.post("/clone")
async def clone(
    text: str = Form(...),
    language: str = Form(DEFAULT_LANGUAGE),
    ref_text: str = Form(""),
    x_vector_only_mode: bool = Form(DEFAULT_X_VECTOR_ONLY),
    file: UploadFile = File(...),
) -> Response:
    if _mode() != "base":
        raise HTTPException(status_code=400, detail="/clone requires a Qwen3-TTS Base model")
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Reference audio file is empty")
    try:
        waveform, sample_rate = sf.read(io.BytesIO(raw), dtype="float32", always_2d=False)
        request = TtsRequest(
            text=text,
            language=language,
            mode="base",
            ref_text=ref_text,
            x_vector_only_mode=x_vector_only_mode,
        )
        with _inference_lock:
            wavs, output_rate = _generate(request, ref_audio=(waveform, int(sample_rate)), ref_text=ref_text)
        return Response(_wav_bytes(wavs, int(output_rate)), media_type="audio/wav")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Qwen3-TTS clone failed: {exc}") from exc


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
