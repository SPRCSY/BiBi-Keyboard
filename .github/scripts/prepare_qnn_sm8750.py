"""Prepare pinned prebuilt artifacts. No NDK, Qualcomm SDK, model conversion or compilation.

The official demo APK is a ZIP container used only as a native-library source;
its code and its bundled generic model are never executed or copied.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tarfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / '.github/qnn-sm8750.lock.json'


def digest(path):
    with Path(path).open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest()


def checked_download(spec, destination, supplied=None):
    if supplied:
        path = Path(supplied).resolve()
        if digest(path) != spec['sha256']:
            raise ValueError(f'Checksum mismatch: {path.name}')
        return path
    if destination.is_file() and digest(destination) == spec['sha256']:
        return destination
    for attempt in range(3):
        try:
            temporary = destination.with_suffix(destination.suffix + '.download')
            request = urllib.request.Request(spec['url'], headers={'User-Agent': 'BiBi-QNN-artifact-setup/1'})
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output, 1024 * 1024)
            if digest(temporary) != spec['sha256']:
                raise ValueError(f'Checksum mismatch: {destination.name}')
            temporary.replace(destination)
            return destination
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)


def prepare(runtime_apk=None, model_archive=None):
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    cache = ROOT / '.qnn-cache'
    cache.mkdir(exist_ok=True)
    apk = checked_download(lock['runtime_apk'], cache / 'official-qnn.apk', runtime_apk)
    model = checked_download(lock['model'], cache / 'sm8750-model.tar.bz2', model_archive)
    dest = ROOT / 'app/qnn-runtime'
    native = dest / 'jniLibs/arm64-v8a'
    assets = dest / 'assets/qnn-sm8750'
    native.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    # Do not use the CPU AAR's JNI libraries. Retain its VAD/denoising API;
    # replace the differing offline binding with the checked-in QNN schema.
    with zipfile.ZipFile(ROOT / 'app/libs/sherpa-onnx-1.13.4.aar') as source:
        api = source.read('classes.jar')
    if hashlib.sha256(api).hexdigest() != lock['api_classes_sha256']:
        raise ValueError('Unexpected sherpa Kotlin API; JNI ABI compatibility is not established')
    # The official QNN APK has a different Moonshine JNI schema despite the
    # same version label. Compile its offline binding schema from reviewed
    # source, while retaining the AAR's other API classes for VAD/denoising.
    overlay = ROOT / 'app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt'
    replaced = set(re.findall(r'^(?:data )?class (\w+)', overlay.read_text(encoding='utf-8'), re.M))
    replaced.add('OfflineRecognizerKt')
    with zipfile.ZipFile(io.BytesIO(api)) as source, zipfile.ZipFile(dest / 'sherpa-api.jar', 'w') as output:
        for entry in source.infolist():
            basename = Path(entry.filename).name.split('$')[0].split('.')[0]
            if entry.filename.startswith('com/k2fsa/sherpa/onnx/') and basename in replaced:
                continue
            output.writestr(entry, source.read(entry))
    with zipfile.ZipFile(apk) as source:
        for name in lock['native_libraries']:
            data = source.read(f'lib/arm64-v8a/{name}')
            if data[:4] != b'\x7fELF':
                raise ValueError(f'Not an ELF native library: {name}')
            (native / name).write_bytes(data)
    required = {'model.bin', 'tokens.txt'}
    found = set()
    with tarfile.open(model, 'r:bz2') as source:
        for member in source:
            name = Path(member.name).name
            if member.isfile() and name in required | {'LICENSE', 'README.md', 'info.txt'}:
                if name in found:
                    raise ValueError(f'Duplicate model artifact: {name}')
                found.add(name)
                with source.extractfile(member) as input_file, (assets / name).open('wb') as output:
                    shutil.copyfileobj(input_file, output)
    if not required.issubset(found):
        raise ValueError(f'Missing model files: {required - found}')
    manifest = dict(soc=lock['soc'], htp=lock['htp'], seconds=lock['seconds'],
                    sherpa_version=lock['sherpa_version'], files={})
    for name in sorted(required):
        path = assets / name
        manifest['files'][name] = {'sha256': digest(path), 'size': path.stat().st_size}
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    (dest / 'ready.json').write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')
    print('Prepared SM8750 / HTP V79, sherpa 1.13.4, 5-second SenseVoice context binary')
    print('Native libraries:', ', '.join(lock['native_libraries']))
    print('Model files:', json.dumps(manifest['files']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime-apk')
    parser.add_argument('--model-archive')
    args = parser.parse_args()
    prepare(args.runtime_apk, args.model_archive)
