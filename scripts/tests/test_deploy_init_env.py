import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


def shell():
    if os.name == 'nt':
        git = shutil.which('git')
        if git:
            git_shell = Path(git).resolve().parent.parent / 'bin' / 'sh.exe'
            if git_shell.is_file():
                return str(git_shell)
        return None
    return shutil.which('sh')


@unittest.skipUnless(shell(), 'A POSIX shell or Git for Windows is required')
class DeployInitEnvTest(unittest.TestCase):
    def initialize(self, folder, openssl_body):
        shutil.copyfile(ROOT / 'deploy/init-env.sh', folder / 'init-env.sh')
        openssl = folder / 'openssl'
        openssl.write_bytes(('#!/bin/sh\n' + openssl_body + '\n').encode('ascii'))
        openssl.chmod(0o700)
        return subprocess.run(
            [shell(), '-c', 'PATH=".:$PATH" sh init-env.sh'], cwd=folder,
            capture_output=True, timeout=10,
        )

    def test_generation_failure_leaves_no_env_and_retry_succeeds(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work)
            result = self.initialize(folder, '''
count=0
if [ -f count ]; then read -r count < count; fi
count=$((count + 1))
printf '%s\\n' "$count" > count
if [ "$count" = 3 ]; then exit 1; fi
printf '%048d\\n' "$count"
''')
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('3', (folder / 'count').read_text().strip())
            self.assertFalse((folder / '.env').exists())
            self.assertFalse(list(folder.glob('.env.tmp.*')))

            retry = self.initialize(folder, "printf '%048d\\n' 1")
            self.assertEqual(0, retry.returncode, retry.stderr)
            config = folder / '.env'
            secrets = [line.split('=', 1)[1] for line in config.read_text().splitlines()
                       if '_PASSWORD=' in line or '_TOKEN=' in line]
            self.assertEqual(4, len(secrets))
            for secret in secrets:
                self.assertRegex(secret, r'^[0-9a-f]{48}$')
            if os.name != 'nt':
                self.assertEqual(0o600, stat.S_IMODE(config.stat().st_mode))
            self.assertFalse(list(folder.glob('.env.tmp.*')))

    def test_generator_success_with_invalid_output_leaves_no_env(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work)
            result = self.initialize(folder, "printf '%048d!\\n' 1 | cut -c2-49")
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((folder / '.env').exists())

    def test_existing_configuration_prevents_generator_call(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work)
            config = folder / '.env'
            config.write_bytes(b'HMDP_WEB_PORT=9090\n')
            result = self.initialize(folder, 'touch generator-called; exit 1')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b'HMDP_WEB_PORT=9090\n', config.read_bytes())
            self.assertFalse((folder / 'generator-called').exists())

    def test_configuration_created_during_generation_is_preserved(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work)
            result = self.initialize(folder, '''
if [ ! -e .env ]; then printf 'HMDP_WEB_PORT=9090\\n' > .env; fi
printf '%048d\\n' 1
''')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b'HMDP_WEB_PORT=9090\n', (folder / '.env').read_bytes())
            self.assertFalse(list(folder.glob('.env.tmp.*')))
