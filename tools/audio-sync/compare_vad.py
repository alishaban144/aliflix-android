"""Development-only measured comparison; no Python/FFmpeg dependency in Aliflix.
TEN-framework/ten-vad manually labelled testset; full source pin in output.
python compare_vad.py --data PATH --model PATH --output PATH --fixture PATH
"""
import argparse, hashlib, json, time
from pathlib import Path
import numpy as np
import onnxruntime as ort
import soundfile as sf
import webrtcvad

p = argparse.ArgumentParser()
p.add_argument('--data', type=Path, required=True)
p.add_argument('--model', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--fixture', type=Path, required=True)
a = p.parse_args()
opts = ort.SessionOptions(); opts.intra_op_num_threads = 1; opts.inter_op_num_threads = 1
s = ort.InferenceSession(str(a.model), opts, providers=['CPUExecutionProvider'])
totals = {name: np.zeros(4, dtype=np.int64) for name in ['webrtc8', 'silero8', 'silero16']}
timings = {name: [] for name in totals}
clips = []

def silero(audio, rate):
    width = rate // 1000 * 32; context = width // 8
    state = np.zeros((2, 1, 128), dtype=np.float32)
    tail = np.zeros(context, dtype=np.float32); probs = []
    for start in range(0, len(audio), width):
        chunk = np.zeros(width, dtype=np.float32)
        piece = audio[start:start + width]; chunk[:len(piece)] = piece
        inputs = {'input': np.concatenate([tail, chunk])[None, :], 'state': state, 'sr': np.array(rate, dtype=np.int64)}
        begin = time.perf_counter_ns()
        result, state = s.run(None, inputs)
        timings[f'silero{rate//1000}'].append((time.perf_counter_ns() - begin) / 1e6)
        probs.append(float(result[0][0])); tail = chunk[-context:]
    return np.array([probs[min(len(probs)-1, int((i + .5) * .02 / .032))] for i in range(len(audio) // (rate // 50))])

for file in sorted(a.data.glob('*.wav')):
    audio, rate = sf.read(file, dtype='float32')
    assert rate == 16000 and audio.ndim == 1
    fields = file.with_suffix('.scv').read_text().strip().split(',')[1:]
    spans = [(float(fields[i]), float(fields[i+1]), int(fields[i+2])) for i in range(0,len(fields),3)]
    mid = (np.arange(len(audio)//320) + .5) * .02
    truth = np.array([any(start <= t < end and label == 1 for start,end,label in spans) for t in mid])
    audio8 = audio[:len(audio)//2*2].reshape(-1,2).mean(axis=1)
    pcm = np.clip(audio8 * 32767, -32768, 32767).astype('<i2')
    vad = webrtcvad.Vad(2); bits = []
    for start in range(0, len(pcm) - 159, 160):
        frame = pcm[start:start+160].tobytes(); begin = time.perf_counter_ns()
        bits.append(vad.is_speech(frame,8000))
        timings['webrtc8'].append((time.perf_counter_ns()-begin)/1e6)
    scores = {'webrtc8': np.array(bits,dtype=np.float32), 'silero8': silero(audio8,8000), 'silero16': silero(audio,16000)}
    for name, probs in scores.items():
        pred = probs >= .5
        totals[name] += [sum(pred & truth), sum(pred & ~truth), sum(~pred & truth), sum(~pred & ~truth)]
    clips.append({'name':file.name, 'audio_sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                  'spans':spans, 'truth':truth.astype(int).tolist(),
                  'webrtc':scores['webrtc8'].tolist(), 'silero8':scores['silero8'].round(5).tolist(),
                  'silero16':scores['silero16'].round(5).tolist()})

result = {'testset': 'TEN-framework/ten-vad@22a3bcd4509d0faaa8eef4881e8af5f39c178950',
          'model_sha256':hashlib.sha256(a.model.read_bytes()).hexdigest(), 'model_bytes':a.model.stat().st_size,
          'onnxruntime':ort.__version__, 'clips':len(clips), 'seconds':sum(len(c['truth']) for c in clips)*.02,
          'platform':'Windows x64 host; CPU timings are not Android device measurements', 'detectors':{}}
for name,(tp,fp,fn,tn) in totals.items():
    samples = np.array(timings[name][2:])
    result['detectors'][name] = {'tp':int(tp),'fp':int(fp),'fn':int(fn),'tn':int(tn),
        'precision':float(tp/(tp+fp)), 'recall':float(tp/(tp+fn)), 'f1':float(2*tp/(2*tp+fp+fn)),
        'mean_ms':float(samples.mean()), 'p95_ms':float(np.percentile(samples,95))}
a.output.parent.mkdir(parents=True,exist_ok=True); a.fixture.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps({'metadata':result,'clips':[{'name':c['name'],'audio_sha256':c['audio_sha256']} for c in clips]},indent=2))
compact = [dict(name=c['name'],audio_sha256=c['audio_sha256'],spans=c['spans'],
                webrtc=''.join('1' if x >= .5 else '0' for x in c['webrtc']),
                silero8=''.join('1' if x >= .5 else '0' for x in c['silero8'])) for c in clips]
a.fixture.write_text(json.dumps({'metadata':result,'clips':compact},separators=(',',':')))
print(json.dumps(result,indent=2))
