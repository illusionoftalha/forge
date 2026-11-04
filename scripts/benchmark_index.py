"""Reproducible index workload. Reports Python allocation peak, not process RSS."""
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import platform
import subprocess
import tempfile
import time
import tracemalloc

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('worker', ROOT/'analysis/worker.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


def main():
    with tempfile.TemporaryDirectory(prefix='forge-benchmark-') as temporary:
        root = Path(temporary).resolve()
        repo = root/'repo'
        repo.mkdir()
        subprocess.run(['git', 'init', str(repo)], check=True, capture_output=True)
        for i in range(1000):
            (repo/f'file-{i:04}.txt').write_bytes((('source line %04d\n' % i) * 1000).encode())
        output = {'platform': platform.platform(), 'python': platform.python_version(),
                  'processor': platform.processor(), 'files': 1000, 'bytes': 17_000_000,
                  'measurement': 'tracemalloc Python allocations; excludes SQLite native memory and OS cache'}
        with closing(worker.connect(root/'workspace')) as db:
            for name in ['cold', 'warm', 'one_edit']:
                if name == 'one_edit': (repo/'file-0500.txt').write_text('changed\n', encoding='utf-8')
                tracemalloc.start()
                start = time.perf_counter()
                stats = worker.update_index(db, repo)
                elapsed = time.perf_counter() - start
                _, peak = tracemalloc.get_traced_memory()
                tracemalloc.stop()
                output[name] = {**stats, 'seconds': round(elapsed, 3), 'python_peak_bytes': peak}
                assert stats['updated'] == {'cold': 1000, 'warm': 0, 'one_edit': 1}[name], stats
                assert peak < 32 * 1024 * 1024, 'Python allocation peak exceeded benchmark budget'
        print(json.dumps(output, indent=2))


if __name__ == '__main__': main()
