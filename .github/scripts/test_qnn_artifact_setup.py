"""Offline checks for native/runtime replacement and model integrity."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('prepare_qnn_sm8750.py')
spec = importlib.util.spec_from_file_location('prepare_qnn', SCRIPT)
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class ArtifactSetupTest(unittest.TestCase):
    def test_only_qnn_native_libraries_and_soc_binary_are_packaged(self):
        original_root = setup.ROOT
        original_lock = setup.LOCK
        try:
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                setup.ROOT = root
                setup.LOCK = root / 'lock.json'
                (root / 'app/libs').mkdir(parents=True)
                api = b'fixed Kotlin API fixture'
                with zipfile.ZipFile(root / 'app/libs/sherpa-onnx-1.13.4.aar', 'w') as archive:
                    archive.writestr('classes.jar', api)
                    archive.writestr('jni/arm64-v8a/libsherpa-onnx-jni.so', b'CPU JNI MUST NOT BE USED')
                apk = root / 'runtime.apk'
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('lib/arm64-v8a/libsherpa-onnx-jni.so', b'\x7fELF QNN JNI fixture')
                    archive.writestr('assets/generic-model/libmodel.so', b'WRONG MODEL')
                model = root / 'model.tar.bz2'
                with tarfile.open(model, 'w:bz2') as archive:
                    for name, data in [('model.bin', b'SM8750 context fixture'), ('tokens.txt', b'tokens')]:
                        member = tarfile.TarInfo(f'soc-package/{name}')
                        member.size = len(data)
                        archive.addfile(member, io.BytesIO(data))
                setup.LOCK.write_text(json.dumps({
                    'soc': 'SM8750', 'htp': 'V79', 'seconds': 5, 'sherpa_version': '1.13.4',
                    'api_classes_sha256': hashlib.sha256(api).hexdigest(),
                    'runtime_apk': {'sha256': setup.digest(apk)},
                    'model': {'sha256': setup.digest(model)},
                    'native_libraries': ['libsherpa-onnx-jni.so']
                }), encoding='utf-8')
                setup.prepare(apk, model)
                dest = root / 'app/qnn-runtime'
                self.assertEqual(b'\x7fELF QNN JNI fixture', (dest / 'jniLibs/arm64-v8a/libsherpa-onnx-jni.so').read_bytes())
                self.assertFalse(any(dest.rglob('libmodel.so')))
                manifest = json.loads((dest / 'assets/qnn-sm8750/manifest.json').read_text())
                self.assertEqual('SM8750', manifest['soc'])
                self.assertEqual(hashlib.sha256(b'SM8750 context fixture').hexdigest(), manifest['files']['model.bin']['sha256'])
        finally:
            setup.ROOT = original_root
            setup.LOCK = original_lock

    def test_changed_download_is_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'unexpected.apk'
            path.write_bytes(b'unexpected bytes')
            with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
                setup.checked_download({'sha256': '0' * 64}, Path(temp) / 'cache', path)


if __name__ == '__main__':
    unittest.main()
