"""Fail release if packaging changes ARM64 binaries or the original neural model."""
import argparse, hashlib, json, zipfile
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('baseline');p.add_argument('apk');p.add_argument('--bridge');p.add_argument('--report',default='native-parity.json')
a=p.parse_args()
assert Path(a.baseline).stat().st_size == 66266641, 'Unexpected baseline APK size'
assert hashlib.sha256(Path(a.baseline).read_bytes()).hexdigest() == '9a6a97bc69d9f6f12dd8df13a5d02e1a04e62a3aea3672bb5c925eaf53703873', 'Unexpected baseline APK digest'
with zipfile.ZipFile(a.baseline) as old, zipfile.ZipFile(a.apk) as new:
    assert old.testzip() is None and new.testzip() is None, 'APK ZIP integrity failure'
    native=[n for n in new.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert native and {n.split('/')[1] for n in native}=={'arm64-v8a'}, 'Release must contain ARM64 only'
    expected={n for n in old.namelist() if n.startswith('lib/arm64-v8a/') and n.endswith('.so')}
    assert set(native)==expected, 'ARM64 library set changed'
    models={n for n in old.namelist() if n.endswith('.onnx')}
    assert models=={n for n in new.namelist() if n.endswith('.onnx')}, 'Neural model set changed'
    hashes={}
    for n in sorted(expected|models):
        before=hashlib.sha256(old.read(n)).hexdigest();after=hashlib.sha256(new.read(n)).hexdigest()
        assert before==after, f'Native/model bytes changed: {n}'
        hashes[n]=after
baseline=Path(a.baseline).stat().st_size;current=Path(a.apk).stat().st_size
report={'baselineBytes':baseline,'apkBytes':current,'reductionBytes':baseline-current,'reductionPercent':(baseline-current)*100/baseline,'sha256':hashlib.sha256(Path(a.apk).read_bytes()).hexdigest(),'parity':hashes}
if a.bridge:
    with zipfile.ZipFile(a.baseline) as old, zipfile.ZipFile(a.bridge) as bridge:
        assert bridge.testzip() is None, 'Compatibility bridge ZIP integrity failure'
        names={n for n in old.namelist() if n.startswith('lib/') and n.endswith('.so')}
        assert names=={n for n in bridge.namelist() if n.startswith('lib/') and n.endswith('.so')}, 'Compatibility bridge lost a device ABI'
        assert {n.split('/')[1] for n in names}=={'arm64-v8a','armeabi-v7a','x86','x86_64'}
        for n in names | models: assert hashlib.sha256(old.read(n)).digest()==hashlib.sha256(bridge.read(n)).digest(), f'Bridge native/model bytes changed: {n}'
        report['compatibilityBridge']={'versionCode':257,'abis':sorted({n.split('/')[1] for n in names}),'nativeAndModelParity':True,'sizeBytes':Path(a.bridge).stat().st_size,'sha256':hashlib.sha256(Path(a.bridge).read_bytes()).hexdigest()}
if a.bridge:
    report['abiPackagingReductionBytes'] = Path(a.bridge).stat().st_size-current
    report['abiPackagingReductionPercent'] = report['abiPackagingReductionBytes']*100/Path(a.bridge).stat().st_size
Path(a.report).write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,indent=2))
