"""Build a synthetic two-commit repository and a ready-to-open investigation."""
from pathlib import Path
import importlib.util
import subprocess
import sys

home = Path(__file__).resolve().parents[1]
target = Path(sys.argv[1] if len(sys.argv)>1 else 'demo').resolve()
target.mkdir(parents=True, exist_ok=False)
repo=target/'repository'
repo.mkdir()

def git(*args):
    return subprocess.check_output(['git','-c','user.name=Forge Demo','-c','user.email=demo@example.invalid',
        '-c','core.hooksPath=/dev/null','-c','commit.gpgsign=false','-C',str(repo),*args],stderr=subprocess.STDOUT).decode().strip()

git('init','-b','main')
(repo/'README.md').write_text('# Settlement service fixture\n\nSynthetic source for a lock-order investigation. Not a runnable banking system.\n')
(repo/'Settlement.java').write_text('''class Settlement {
    void commit(Object account, Object journal) {
        synchronized (account) {
            synchronized (journal) { /* commit batch */ }
        }
    }
}
''')
(repo/'Journal.java').write_text('''class Journal {
    void flush(Object account, Object journal) {
        synchronized (account) {
            synchronized (journal) { /* flush batch */ }
        }
    }
}
''')
git('add','.');git('commit','-m','Use consistent account-then-journal locking')
(repo/'Journal.java').write_text('''class Journal {
    void flush(Object account, Object journal) {
        synchronized (journal) {
            synchronized (account) { /* lock-order regression */ }
        }
    }
}
''')
git('add','.');git('commit','-m','Introduce a lock-order inversion in the demo')
spec=importlib.util.spec_from_file_location('worker',home/'analysis/worker.py');worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)
workspace=target/'workspace'
def call(action,**args):return worker.handle({'version':1,'workspace':str(workspace),'action':action,**args})
call('open',repository=str(repo))
diff=call('diff',sha=git('rev-parse','HEAD'))
call('capture',kind='git-diff',source=diff['source'],content=diff['text'])
call('notes',body='Hypothesis: Journal.flush changed the lock order.\n\nNext: import fixtures/incident.txt, inspect the monitor cycle, capture the snapshot, and export the investigation.\n')
print('Demo workspace:',workspace)
print('Launch: java -jar build/forge.jar "'+str(workspace)+'"')
