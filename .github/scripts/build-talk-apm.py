"""Build pinned WebRTC AEC3 JNI for Android; no vendor DSP or model replacement."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
VERSION = '2.1'
SOURCE_SHA = 'ae9302824b2038d394f10213cab05312c564a038434269f11dbf68f511f9f9fe'
NDK = '27.2.12479018'
ABIS = {
    'arm64-v8a': ('aarch64-linux-android', 'aarch64', 'aarch64'),
    'armeabi-v7a': ('armv7a-linux-androideabi', 'arm', 'armv7'),
    'x86': ('i686-linux-android', 'x86', 'i686'),
    'x86_64': ('x86_64-linux-android', 'x86_64', 'x86_64'),
}


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', choices=ABIS, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert f'Pkg.Revision = {NDK}' in (args.ndk / 'source.properties').read_text()
    work = ROOT / '.validation' / 'apm-build' / args.abi
    work.mkdir(parents=True, exist_ok=True)
    archive = work / f'webrtc-audio-processing-{VERSION}.tar.xz'
    if not archive.exists():
        urllib.request.urlretrieve(f'https://gstreamer.freedesktop.org/src/mirror/webrtc-audio-processing/{archive.name}', archive)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == SOURCE_SHA
    source = work / f'webrtc-audio-processing-{VERSION}'
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(work, filter='data')
    # Meson's wrap also pins the Abseil source AND its build overlay by SHA-256.
    triple, family, cpu = ABIS[args.abi]
    bin_dir = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    compiler = bin_dir / f'{triple}29-clang++'
    common = ['-fvisibility=hidden', '-ffunction-sections', '-fdata-sections',
              f'-ffile-prefix-map={ROOT}=.', '-fPIC']
    cross = work / 'android.ini'
    cross.write_text('\n'.join([
        '[binaries]', f"c = '{bin_dir / (triple + '29-clang')}'",
        f"cpp = '{compiler}'", f"ar = '{bin_dir / 'llvm-ar'}'",
        f"strip = '{bin_dir / 'llvm-strip'}'", "pkg-config = 'false'",
        '[host_machine]', "system = 'android'", f"cpu_family = '{family}'",
        f"cpu = '{cpu}'", "endian = 'little'", '[built-in options]',
        f'c_args = {common!r}', f'cpp_args = {common!r}',
    ]) + '\n')
    build = work / 'build'
    run('meson', 'setup', build, source, '--cross-file', cross,
        '--default-library=static', '--buildtype=release', '-Db_staticpic=true',
        '-Dgnustl=disabled', '--wrap-mode=forcefallback')
    run('meson', 'compile', '-C', build, '-j', '3')
    libraries = sorted(build.rglob('*.a'))
    assert any(p.name == 'libwebrtc-audio-processing-2.a' for p in libraries)
    out = args.output / args.abi / 'libaliflix_apm.so'
    out.parent.mkdir(parents=True, exist_ok=True)
    absl = source / 'subprojects' / 'abseil-cpp-20240722.0'
    run(compiler, '-std=c++17', '-shared', '-O2', *common,
        '-DWEBRTC_POSIX', '-DWEBRTC_ANDROID', '-DWEBRTC_LINUX', '-DWEBRTC_APM_DEBUG_DUMP=0', '-DNDEBUG',
        '-I' + str(source / 'webrtc'), '-I' + str(absl),
        ROOT / 'app/src/mobile/cpp/talk_apm.cpp', '-Wl,--start-group', *libraries,
        '-Wl,--end-group', '-static-libstdc++', '-Wl,--exclude-libs,ALL',
        '-Wl,--gc-sections', '-Wl,-z,defs', '-Wl,-z,max-page-size=16384',
        '-Wl,-z,common-page-size=16384', '-llog', '-latomic', '-ldl', '-lm', '-o', out)
    run(bin_dir / 'llvm-strip', '--strip-unneeded', out)
    manifest = {'abi': args.abi, 'webrtcAudioProcessing': VERSION,
                'sourceSha256': SOURCE_SHA, 'abseil': '20240722.0',
                'ndk': NDK, 'minSdk': 29, 'pageSize': 16384,
                'sha256': hashlib.sha256(out.read_bytes()).hexdigest(), 'bytes': out.stat().st_size}
    (out.parent / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for filename in ['COPYING', 'AUTHORS']:
        shutil.copyfile(source / filename, out.parent / ('WEBRTC-' + filename))
    shutil.copyfile(absl / 'LICENSE', out.parent / 'ABSEIL-LICENSE')


if __name__ == '__main__':
    main()
