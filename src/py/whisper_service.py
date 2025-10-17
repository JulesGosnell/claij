from fastapi import FastAPI, UploadFile
import whisper
import io
import torch
import numpy as np
import soundfile as sf
import logging

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

app = FastAPI()
device = "cuda" if torch.cuda.is_available() else "cpu"
model = whisper.load_model("small", device=device)  # tiny/small/medium/large-v3

logger.info(f"Whisper service started with model 'small' on device '{device}'")

@app.post("/transcribe")
async def transcribe(audio: UploadFile):
    logger.info(f"Received audio file for transcription: {audio.filename}")
    content = await audio.read()
    logger.debug(f"Audio file size: {len(content)} bytes")
    
    with open("temp.wav", "wb") as f:
        f.write(content)
    audio_data, sample_rate = sf.read("temp.wav")
    
    if sample_rate != 16000:
        raise ValueError("Audio must be 16kHz")
    
    # Convert to float32 to match Whisper's expectations
    audio_data = audio_data.astype(np.float32)
    logger.debug(f"Processing audio: {len(audio_data)} samples at {sample_rate}Hz")
    
    result = model.transcribe(audio_data)
    transcribed_text = result["text"]
    
    logger.info(f"Transcription result: '{transcribed_text}'")
    
    return {"text": transcribed_text}
