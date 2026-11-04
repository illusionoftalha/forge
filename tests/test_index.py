from test_worker import Investigation, worker
from unittest.mock import patch
from contextlib import closing
import sqlite3
import unittest
import subprocess
import sys
import time
from pathlib import Path


class RepositoryIndex(unittest.TestCase):
    setUp = Investigation.setUp
    git = Investigation.git
    call = Investigation.call
    # Reuse real Git setup without rerunning the base class's test cases.
    def test_incremental_refresh_and_search(self):
        with patch.object(worker, 'read_text', side_effect=AssertionError('unchanged file read')):
            result = self.call('files')['index']
        self.assertEqual(result['updated'], 0)
        self.assertEqual(result['reused'], 2)
        (self.repo/'sample.py').write_text('needle\nsecond needle\n', encoding='utf-8')
        hits = self.call('search', query='NEEDLE')['hits']
        self.assertEqual([h['line'] for h in hits], [1, 2])
        self.assertEqual(self.call('changes')['changes'][0]['kind'], 'modified')

    def test_exact_rename_and_delete(self):
        (self.repo/'sample.py').rename(self.repo/'renamed.py')
        self.call('files')
        changes = self.call('changes')['changes']
        self.assertEqual(changes, [{'kind': 'renamed', 'path': 'renamed.py', 'previous': 'sample.py'}])
        self.call('files')
        self.assertEqual(self.call('changes')['changes'], changes)
        (self.repo/'renamed.py').unlink()
        self.call('files')
        self.assertEqual(self.call('changes')['changes'][0]['kind'], 'removed')

    def test_ambiguous_rename_is_not_claimed(self):
        text = (self.repo/'sample.py').read_text()
        (self.repo/'sample.py').unlink()
        for name in ['one.py', 'two.py']: (self.repo/name).write_text(text)
        self.call('files')
        self.assertEqual(sorted(c['kind'] for c in self.call('changes')['changes']), ['added','added','removed'])

    def test_interruption_rolls_back_generation(self):
        (self.repo/'a.py').write_text('new entry')
        (self.repo/'sample.py').write_text('new content')
        original = worker.read_text
        def interrupted(path):
            if path.name == 'sample.py': raise KeyboardInterrupt('simulated interruption')
            return original(path)
        with patch.object(worker, 'read_text', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt): self.call('files')
        with closing(sqlite3.connect(self.workspace/'forge.sqlite')) as db:
            self.assertIsNone(db.execute("SELECT path FROM source_index WHERE path='a.py'").fetchone())
            self.assertIn('after', db.execute("SELECT content FROM source_index WHERE path='sample.py'").fetchone()[0])
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
        self.assertEqual(self.call('files')['index']['updated'], 2)

    def test_budget_failure_preserves_index(self):
        (self.repo/'a.py').write_text('a'*100)
        with patch.object(worker, 'MAX_INDEX', 20):
            self.assertIn('32 MiB', self.call('files')['index_warning'])
            with self.assertRaises(worker.IndexLimit): self.call('search', query='after')
        with closing(sqlite3.connect(self.workspace/'forge.sqlite')) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM source_index').fetchone()[0], 2)

    def test_rebuild_and_binary_invalidation(self):
        self.assertEqual(self.call('files', rebuild=True)['index']['updated'], 2)
        (self.repo/'sample.py').write_bytes(b'after\0binary')
        result = self.call('search', query='after')
        self.assertEqual(result['hits'], [])
        self.assertEqual(result['skipped'], 1)

    def test_killed_worker_recovers_on_reopen(self):
        (self.repo/'a.py').write_text('uncommitted index row')
        (self.repo/'sample.py').write_text('changed')
        marker = self.root/'started'
        script = self.root/'interrupt.py'
        script.write_text('''import importlib.util, pathlib, time
spec = importlib.util.spec_from_file_location('worker', %r)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)
original = worker.read_text
def slow(path):
    if path.name == 'sample.py':
        pathlib.Path(%r).touch()
        time.sleep(60)
    return original(path)
worker.read_text = slow
worker.handle({'version': 1, 'action': 'files', 'workspace': %r})
''' % (str(Path(worker.__file__).resolve()), str(marker), str(self.workspace)))
        process = subprocess.Popen([sys.executable, str(script)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 10
            while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertTrue(marker.exists(), 'worker reached open transaction')
        finally:
            process.kill()
            process.communicate(timeout=5)
        with closing(sqlite3.connect(self.workspace/'forge.sqlite')) as db:
            self.assertIsNone(db.execute("SELECT path FROM source_index WHERE path='a.py'").fetchone())
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
        self.assertEqual(self.call('files')['index']['updated'], 2)

    def test_file_limit_refuses_partial_generation(self):
        with patch.object(worker, 'MAX_FILES', 1):
            self.assertIn('index not changed', self.call('files')['index_warning'])

    def test_search_line_numbers_match_desktop_newlines(self):
        (self.repo/'sample.py').write_bytes('first\r\nsecond\u2028needle\nneedle'.encode())
        self.assertEqual([h['line'] for h in self.call('search', query='needle')['hits']], [2, 3])

    def test_schema_one_migration_preserves_evidence(self):
        self.call('notes', body='keep this')
        item = self.call('capture', kind='note', source='fixture', content='immutable')
        with closing(sqlite3.connect(self.workspace/'forge.sqlite')) as db:
            db.execute('DROP TABLE source_index')
            db.execute('DROP TABLE index_changes')
            db.execute('PRAGMA user_version=1')
            db.commit()
        self.assertEqual(self.call('state')['notes'], 'keep this')
        self.assertEqual(self.call('evidence', id=item['id'])['text'], 'immutable')
        self.call('files')
        with closing(sqlite3.connect(self.workspace/'forge.sqlite')) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 2)


del Investigation

if __name__ == '__main__': unittest.main()
