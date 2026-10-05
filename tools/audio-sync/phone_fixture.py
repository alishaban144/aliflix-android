"""Build a development-only real-speech fixture for an attached Android phone.
No raw audio or Python runtime is included in the production app.
"""
import argparse, json, wave
from pathlib import Path
import numpy as np
import soundfile as sf

p = argparse.ArgumentParser()
p.add_argument('--data', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args(); audio = []; cues = []; frames = 0
for i, file in enumerate(sorted(a.data.glob('*.wav'))):
    pcm, sr = sf.read(file, dtype='int16'); assert sr == 16000 and pcm.ndim == 1
    fields = file.with_suffix('.scv').read_text().strip().split(',')[1:]
    for j in range(0, len(fields), 3):
        start, end, label = float(fields[j]), float(fields[j+1]), int(fields[j+2])
        if label == 1:
            while end > start:
                stop = min(end, start + 10)
                cues.append([frames/16000 + start, frames/16000 + stop, 'هذا حوار عربي.'])
                start = stop
    audio.append(pcm); frames += len(pcm)
    gap = np.zeros(32000, dtype='int16'); audio.append(gap); frames += len(gap)
a.output.mkdir(parents=True, exist_ok=True)
with wave.open(str(a.output/'audio-sync-validation.wav'), 'wb') as wav:
    wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000)
    wav.writeframes(np.concatenate(audio).astype('<i2').tobytes())
(a.output/'audio-sync-validation.json').write_text(json.dumps(cues, ensure_ascii=False), encoding='utf-8')
print(json.dumps({'seconds':frames/16000, 'cues':len(cues)}))
