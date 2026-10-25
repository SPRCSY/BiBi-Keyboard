"""Run with Python 3 and PyYAML; bash must be on PATH or supplied as BASH_EXE."""
import ast
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/release-on-version-bump.yml'
DOC = yaml.load(WORKFLOW.read_text(encoding='utf-8'), Loader=yaml.BaseLoader)
STEPS = {step['name']: step for step in DOC['jobs']['release']['steps']}
BASH = os.environ.get('BASH_EXE') or shutil.which('bash')
SECRET_NAMES = ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')


def python_block(name):
    return STEPS[name]['run'].split("python3 - <<'PY'\n", 1)[1].split('\nPY', 1)[0]


def shell(name, env, cwd=ROOT):
    script = re.sub(r'\$\{\{.*?\}\}', 'EXPRESSION', STEPS[name]['run'])
    return subprocess.run([BASH, '-eo', 'pipefail', '-c', script], env=env,
                          cwd=cwd, capture_output=True, text=True, encoding='utf-8')


class ReleaseWorkflowTest(unittest.TestCase):
    def test_shell_and_python_syntax(self):
        self.assertTrue(BASH, 'bash is required')
        for name, step in STEPS.items():
            if 'run' not in step:
                continue
            with self.subTest(step=name):
                script = re.sub(r'\$\{\{.*?\}\}', 'EXPRESSION', step['run'])
                result = subprocess.run([BASH, '-n'], input=script, text=True,
                                        capture_output=True, encoding='utf-8')
                self.assertEqual(0, result.returncode, result.stderr)
                if "python3 - <<'PY'\n" in step['run']:
                    ast.parse(python_block(name))

    def test_manual_build_does_not_publish_by_default(self):
        inputs = DOC['on']['workflow_dispatch']['inputs']
        self.assertEqual('false', inputs['publish_release']['default'])
        for name in ('Create GitHub Release', 'Update version.json', 'Commit and push version.json'):
            self.assertIn("github.event_name == 'push' || inputs.publish_release", STEPS[name]['if'])
        self.assertNotIn('Build Debug APK (fallback)', STEPS)
        names = list(STEPS)
        self.assertLess(names.index('Upload signed Release APKs'), names.index('Create GitHub Release'))
        self.assertLess(names.index('Create GitHub Release'), names.index('Update version.json'))
        self.assertEqual('${{ github.sha }}', STEPS['Create GitHub Release']['with']['target_commitish'])
        self.assertEqual('always()', STEPS['Remove temporary signing key']['if'])

    def test_manual_build_runs_without_version_bump(self):
        self.assertEqual("${{ github.event_name == 'workflow_dispatch' }}",
                         STEPS['Detect version change']['env']['FORCE_RELEASE'])
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'output'
            for manual, expected in [('true', 'true'), ('false', 'false')]:
                env = dict(os.environ, FORCE_RELEASE=manual, GITHUB_EVENT_BEFORE='HEAD',
                           GITHUB_OUTPUT=str(output))
                output.write_text('', encoding='utf-8')
                result = subprocess.run([sys.executable, '-c', python_block('Detect version change')],
                                        cwd=ROOT, env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(f'changed={expected}', output.read_text(encoding='utf-8'))

    def test_missing_secrets_fail_without_printing_values(self):
        for missing in SECRET_NAMES:
            env = dict(os.environ)
            env.update({name: 'private-placeholder-do-not-log' for name in SECRET_NAMES})
            env.pop(missing)
            result = shell('Validate signing secrets', env)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(missing, result.stdout)
            self.assertNotIn('private-placeholder-do-not-log', result.stdout + result.stderr)
        env.update({name: 'private-placeholder-do-not-log' for name in SECRET_NAMES})
        self.assertEqual(0, shell('Validate signing secrets', env).returncode)

    def test_existing_tag_is_allowed_for_artifacts_but_not_replaced(self):
        with tempfile.TemporaryDirectory() as temp:
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=temp, text=True).strip()
            git('init', '-q')
            git('-c', 'user.name=Workflow test', '-c', 'user.email=test@example.invalid',
                'commit', '--allow-empty', '-qm', 'first')
            original = git('rev-parse', 'HEAD')
            git('tag', 'v1.0')
            env = dict(os.environ, VERSION_NAME='1.0', VERSION_CODE='1',
                       GITHUB_OUTPUT=str(Path(temp) / 'output'), GITHUB_SHA='different-commit')
            env['PUBLISH_RELEASE'] = 'false'
            self.assertEqual(0, shell('Compose current tag', env, temp).returncode)
            env['PUBLISH_RELEASE'] = 'true'
            self.assertNotEqual(0, shell('Compose current tag', env, temp).returncode)
            env['GITHUB_SHA'] = original
            self.assertEqual(0, shell('Compose current tag', env, temp).returncode)

    def test_version_metadata_points_to_current_fork(self):
        with tempfile.TemporaryDirectory() as temp:
            env = dict(os.environ, VERSION='4.5.3', VERSION_CODE='179', TAG='v4.5.3',
                       GITHUB_REPOSITORY='example/fork', PYTHONIOENCODING='utf-8')
            result = subprocess.run([sys.executable, '-c', python_block('Update version.json')],
                                    cwd=temp, env=env, capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads((Path(temp) / 'version.json').read_text(encoding='utf-8'))
            self.assertEqual('https://github.com/example/fork/releases/tag/v4.5.3', data['download_url'])
            self.assertEqual(179, data['version_code'])


if __name__ == '__main__':
    unittest.main()
