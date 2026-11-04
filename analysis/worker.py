"""Forge protocol v1: one JSON request on stdin, one JSON response on stdout.

Only Git's read operations run against inspected repositories. Repository code,
build scripts, hooks, external diff drivers, and text converters are never run.
"""
from __future__ import annotations

from contextlib import closing
import hashlib
import html
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import time

PROTOCOL = 1
MAX_FILE = 2 * 1024 * 1024
MAX_OUTPUT = 4 * 1024 * 1024
MAX_FILES = 20000
SKIP = {'.git', '.hg', '.svn', 'node_modules', '.venv', 'venv', '__pycache__', 'build', 'target', 'dist'}


def git(repo, *args):
    env = {**os.environ, 'GIT_TERMINAL_PROMPT': '0', 'GIT_OPTIONAL_LOCKS': '0',
           'GIT_CONFIG_NOSYSTEM': '1', 'GIT_PAGER': 'cat'}
    command = ['git', '--no-pager', '-c', 'core.fsmonitor=false', '-c',
               'core.hooksPath=/dev/null', '-c', 'color.ui=false', '-c', 'log.showSignature=false', '-C', str(repo), *args]
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output,
                                   stderr=subprocess.STDOUT, env=env)
        deadline = time.monotonic() + 15
        try:
            while process.poll() is None:
                if time.monotonic() > deadline:
                    raise ValueError('Git operation exceeded 15 seconds')
                if os.fstat(output.fileno()).st_size > MAX_OUTPUT:
                    raise ValueError('Git output exceeds 4 MiB; narrow the investigation')
                time.sleep(.02)
            output.seek(0)
            data = output.read(MAX_OUTPUT + 1)
            if len(data) > MAX_OUTPUT:
                raise ValueError('Git output exceeds 4 MiB')
            text = data.decode('utf-8', errors='replace')
            if process.returncode:
                raise ValueError(text.strip()[:1200] or 'Git operation failed')
            return text
        finally:
            if process.poll() is None:
                process.kill()
            process.wait()


def connect(workspace):
    root = Path(workspace).resolve()
    root.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(root / 'forge.sqlite', timeout=5)
    try:
        version = db.execute('PRAGMA user_version').fetchone()[0]
        if version > 1:
            raise ValueError('Workspace was created by a newer Forge version')
        with db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS evidence(
                    id INTEGER PRIMARY KEY, kind TEXT NOT NULL, source TEXT NOT NULL,
                    content TEXT NOT NULL, sha256 TEXT NOT NULL, captured TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS notebook(id INTEGER PRIMARY KEY CHECK(id=1),body TEXT NOT NULL);
                INSERT OR IGNORE INTO notebook VALUES(1,'');
                PRAGMA user_version=1;
            ''')
        return db
    except Exception:
        db.close()
        raise


def repository(db):
    row = db.execute("SELECT value FROM settings WHERE key='repository'").fetchone()
    if not row:
        raise ValueError('Open a repository first')
    root = Path(row[0]).resolve()
    if not root.is_dir():
        raise ValueError('The saved repository is no longer available')
    return root


def safe_file(root, relative):
    requested = Path(relative)
    if requested.is_absolute() or '..' in requested.parts or not requested.parts:
        raise ValueError('Expected a relative repository path')
    current = root
    for part in requested.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('Symbolic links are not inspected')
    path = current.resolve()
    if not path.is_relative_to(root) or not path.is_file():
        raise ValueError('File is outside the repository or no longer exists')
    return path


def read_text(path):
    with path.open('rb') as stream:
        data = stream.read(MAX_FILE + 1)
    if len(data) > MAX_FILE:
        raise ValueError('File exceeds the 2 MiB preview limit')
    if b'\0' in data:
        raise ValueError('Binary files are not previewed')
    return data.decode('utf-8', errors='replace')


def files(root):
    # Git provides tracked and untracked non-ignored files; no directory crawling.
    raw = git(root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z')
    paths = sorted(set(p for p in raw.split('\0') if p and not (set(Path(p).parts) & SKIP)))
    limited = len(paths) > MAX_FILES
    return paths[:MAX_FILES], limited


def snapshot(db):
    row = db.execute("SELECT value FROM settings WHERE key='repository'").fetchone()
    evidence = [dict(zip(('id','kind','source','sha256','captured'), r)) for r in db.execute(
        'SELECT id,kind,source,sha256,captured FROM evidence ORDER BY id DESC')]
    return {'repository': row[0] if row else '', 'notes': db.execute('SELECT body FROM notebook WHERE id=1').fetchone()[0],
            'evidence': evidence}


def export_report(db, target):
    dest = Path(target).resolve()
    esc = html.escape
    state = snapshot(db)
    pieces = []
    for kind, source, content, digest, captured in db.execute(
            'SELECT kind,source,content,sha256,captured FROM evidence ORDER BY id'):
        pieces.append(f'<section><div class="tag">{esc(kind)} · {esc(captured)}</div><h2>{esc(source)}</h2>'
                      f'<details open><summary>Captured evidence</summary><pre>{esc(content)}</pre></details>'
                      f'<p class="hash">SHA-256 {digest}</p></section>')
    page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Forge investigation</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{background:#111820;color:#e4e9ed;font:15px/1.6 system-ui;margin:0}main{max-width:1080px;margin:auto;padding:48px 24px}h1{font-size:42px;margin:8px 0}h2{font-size:19px;overflow-wrap:anywhere}.tag{font:12px monospace;color:#e9b975;letter-spacing:1px}section{padding:24px;background:#1a2430;border:1px solid #354150;border-radius:10px;margin:20px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.7 monospace}.hash{color:#98a8b8;font:11px monospace;overflow-wrap:anywhere}summary{cursor:pointer;color:#e9b975}footer{color:#98a8b8}
</style><main><div class="tag">FORGE / INVESTIGATION RECORD</div><h1>Follow the evidence.</h1>'''
    page += '<p>' + esc(state['repository']) + '</p><section><h2>Investigator notes</h2><pre>' + esc(state['notes']) + '</pre></section>'
    page += ''.join(pieces) + '<footer>Captured snapshots, not live views. Hashes identify captured UTF-8 text; they do not authenticate its origin. Generated locally by Forge.</footer></main></html>'
    with dest.open('x', encoding='utf-8') as output:
        output.write(page)
    return {'path': str(dest), 'items': len(pieces)}


def handle(request):
    if request.get('version') != PROTOCOL:
        raise ValueError('Unsupported protocol version')
    workspace = request.get('workspace')
    if not isinstance(workspace, str) or not workspace:
        raise ValueError('workspace must be a directory path')
    action = request.get('action')
    with closing(connect(workspace)) as db:
        if action == 'state':
            return snapshot(db)
        if action == 'open':
            root = Path(request['repository']).resolve(strict=True)
            actual = Path(git(root, 'rev-parse', '--show-toplevel').strip()).resolve()
            paths, limited = files(actual)
            with db:
                db.execute("INSERT OR REPLACE INTO settings VALUES('repository',?)", (str(actual),))
            return {'repository': str(actual), 'files': paths, 'limited': limited,
                    'status': git(actual, 'status', '--short', '--untracked-files=no')}
        if action == 'files':
            root = repository(db)
            paths, limited = files(root)
            return {'repository': str(root), 'files': paths, 'limited': limited,
                    'status': git(root, 'status', '--short', '--untracked-files=no')}
        if action == 'read':
            root = repository(db)
            path = safe_file(root, request['path'])
            text = read_text(path)
            return {'source': str(path), 'text': text, 'sha256': hashlib.sha256(text.encode()).hexdigest()}
        if action == 'history':
            root = repository(db)
            # Unit separator is safe for fields except arbitrary commit subjects;
            # split at most twice, preserving any delimiter in the subject.
            raw = git(root, 'log', '--all', '-80', '--format=%H%x1f%cs%x1f%s')
            return {'commits': [dict(zip(('sha','date','subject'), line.split('\x1f',2))) for line in raw.splitlines() if line]}
        if action == 'diff':
            root = repository(db)
            sha = request['sha']
            if not re.fullmatch('[0-9a-fA-F]{40,64}', sha):
                raise ValueError('Expected a full commit SHA')
            text = git(root, 'show', '--no-ext-diff', '--no-textconv', '--format=fuller', '--stat', '--patch', sha, '--')
            return {'source': str(root) + '@' + sha, 'text': text}
        if action == 'search':
            root = repository(db)
            query = request['query']
            if not isinstance(query,str) or not query.strip():
                raise ValueError('Search requires nonempty literal text')
            paths, limited = files(root)
            hits, skipped = [], 0
            budget = 32 * 1024 * 1024
            for relative in paths:
                try:
                    path = safe_file(root, relative)
                    if path.stat().st_size > budget:
                        limited = True
                        break
                    text = read_text(path)
                    budget -= len(text.encode('utf-8'))
                except (OSError, ValueError):
                    skipped += 1
                    continue
                for number, line in enumerate(text.splitlines(), 1):
                    if query.casefold() in line.casefold():
                        hits.append({'path': relative, 'line': number, 'text': line[:400]})
                        if len(hits) >= 300:
                            return {'hits': hits, 'limited': True, 'skipped': skipped}
            return {'hits': hits, 'limited': limited, 'skipped': skipped}
        if action == 'capture':
            kind, source, content = request['kind'], request['source'], request['content']
            if not all(isinstance(v,str) for v in (kind,source,content)) or not kind or not source:
                raise ValueError('Evidence requires kind, source and text')
            if len(content.encode('utf-8')) > MAX_OUTPUT or len(source)>4096 or len(kind)>80:
                raise ValueError('Evidence exceeds size limits')
            digest = hashlib.sha256(content.encode('utf-8')).hexdigest()
            captured = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
            with db:
                cursor = db.execute('INSERT INTO evidence(kind,source,content,sha256,captured) VALUES(?,?,?,?,?)',
                                    (kind,source,content,digest,captured))
            return {'id': cursor.lastrowid, 'sha256': digest}
        if action == 'evidence':
            row = db.execute('SELECT kind,source,content,sha256,captured FROM evidence WHERE id=?',(request['id'],)).fetchone()
            if row is None:
                raise ValueError('Evidence no longer exists')
            return dict(zip(('kind','source','text','sha256','captured'),row))
        if action == 'notes':
            body = request['body']
            if not isinstance(body,str) or len(body.encode('utf-8')) > MAX_FILE:
                raise ValueError('Notes must be text under 2 MiB')
            with db:
                db.execute('UPDATE notebook SET body=? WHERE id=1',(body,))
            return {'saved': True}
        if action == 'export':
            return export_report(db, request['path'])
        raise ValueError('Unknown action: ' + str(action))


def main():
    try:
        raw = sys.stdin.buffer.read(8 * 1024 * 1024 + 1)
        if len(raw) > 8 * 1024 * 1024:
            raise ValueError('Request too large')
        request = json.loads(raw)
        if not isinstance(request,dict):
            raise ValueError('Expected a JSON object')
        result = {'version': PROTOCOL, 'ok': True, 'data': handle(request)}
    except (ValueError, KeyError, OSError, sqlite3.Error) as error:
        result = {'version': PROTOCOL, 'ok': False, 'error': str(error)}
    sys.stdout.buffer.write(json.dumps(result, ensure_ascii=True).encode('utf-8'))


if __name__ == '__main__':
    main()
