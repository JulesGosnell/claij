from fastapi import FastAPI, UploadFile
import whisper
import io
import torch
import numpy as np
import soundfile as sf

app = FastAPI()
device = "cuda" if torch.cuda.is_available() else "cpu"
model = whisper.load_model("small", device=device)  # tiny/small/medium/large-v3

@app.post("/transcribe")
async def transcribe(audio: UploadFile):
    content = await audio.read()
    with open("temp.wav", "wb") as f:
        f.write(content)
    audio_data, sample_rate = sf.read("temp.wav")
    if sample_rate != 16000:
        raise ValueError("Audio must be 16kHz")
    # Convert to float32 to match Whisper's expectations
    audio_data = audio_data.astype(np.float32)
    result = model.transcribe(audio_data)
    return {"text": result["text"]}
