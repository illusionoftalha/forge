from pathlib import Path
import hashlib
import importlib.util
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('worker', ROOT / 'analysis/worker.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class Investigation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.workspace = self.root / 'workspace'
        self.git('init', '-b', 'main')
        (self.repo / 'sample.py').write_text('print("before")\n', encoding='utf-8')
        (self.repo / '.gitignore').write_text('secret.txt\n', encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'Initial fixture')
        (self.repo / 'sample.py').write_text('print("after")\n', encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'Change fixture')
        self.call('open', repository=str(self.repo))

    def git(self, *args):
        return subprocess.check_output(['git','-c','user.name=Fixture','-c','user.email=fixture@example.invalid',
            '-c','core.hooksPath=/dev/null','-c','commit.gpgsign=false','-C',str(self.repo),*args], stderr=subprocess.STDOUT).decode().strip()

    def call(self, action, **kwargs):
        return worker.handle({'version': 1, 'workspace': str(self.workspace), 'action': action, **kwargs})

    def test_complete_investigation_and_restore(self):
        commits = self.call('history')['commits']
        self.assertEqual(len(commits), 2)
        diff = self.call('diff', sha=commits[0]['sha'])
        self.assertIn('+print("after")', diff['text'])
        capture = self.call('capture', kind='git-diff', source=diff['source'], content=diff['text'])
        self.assertEqual(capture['sha256'], hashlib.sha256(diff['text'].encode()).hexdigest())
        self.call('notes', body='Investigate this regression.')
        state = self.call('state')
        self.assertEqual(state['notes'], 'Investigate this regression.')
        self.assertEqual(len(state['evidence']), 1)
        (self.repo / 'sample.py').write_text('edited again')
        self.assertEqual(self.call('evidence', id=capture['id'])['text'], diff['text'])
        report = self.root / 'report.html'
        self.call('export', path=str(report))
        self.assertIn('Investigate this regression.', report.read_text(encoding='utf-8'))

    def test_export_escapes_and_refuses_overwrite(self):
        self.call('capture', kind='test', source='<script>x</script>', content='<script>alert(1)</script>')
        path = self.root / 'report.html'
        self.call('export', path=str(path))
        before = path.read_bytes()
        self.assertNotIn(b'<script>', before)
        with self.assertRaises(FileExistsError): self.call('export', path=str(path))
        self.assertEqual(path.read_bytes(), before)

    def test_traversal_and_absolute_paths_rejected(self):
        for path in ('../outside', str(self.root / 'outside')):
            with self.assertRaises(ValueError): self.call('read', path=path)

    def test_search_respects_ignore_rules(self):
        (self.repo / 'secret.txt').write_text('after')
        hits = self.call('search', query='AFTER')['hits']
        self.assertEqual([h['path'] for h in hits], ['sample.py'])

    def test_binary_rejected(self):
        (self.repo / 'binary').write_bytes(b'a\0b')
        with self.assertRaises(ValueError): self.call('read', path='binary')

    def test_oversize_file_rejected(self):
        (self.repo / 'large').write_bytes(b'x' * (worker.MAX_FILE + 1))
        with self.assertRaises(ValueError): self.call('read', path='large')

    def test_commit_argument_injection_rejected(self):
        with self.assertRaises(ValueError): self.call('diff', sha='--output=stolen')

    def test_unsupported_protocol(self):
        with self.assertRaises(ValueError): worker.handle({'version': 99})

    def test_unknown_action(self):
        with self.assertRaises(ValueError): self.call('execute')

    def test_empty_repository(self):
        empty = self.root / 'empty'
        empty.mkdir()
        subprocess.run(['git','init',str(empty)], check=True,capture_output=True)
        self.call('open', repository=str(empty))
        self.assertEqual(self.call('history')['commits'], [])

    def test_protocol_error_envelope(self):
        p = subprocess.run([sys.executable,str(ROOT/'analysis/worker.py')],input=b'not json',capture_output=True)
        self.assertEqual(p.returncode, 0)
        self.assertFalse(json.loads(p.stdout)['ok'])

    def test_no_repository_modifications(self):
        before = (self.repo / 'sample.py').read_bytes()
        self.call('files'); self.call('search',query='after');self.call('history')
        self.assertEqual((self.repo / 'sample.py').read_bytes(), before)
        self.assertEqual(self.git('status','--porcelain'), '')


if __name__ == '__main__': unittest.main()
