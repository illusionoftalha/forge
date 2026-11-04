"""Package a runnable, inspectable distribution; exclude local workspace data."""
import argparse
import hashlib
from pathlib import Path
import re
import zipfile

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version',default='0.2.1')
args=parser.parse_args()
if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-z0-9.]+)?',args.version):
    parser.error('version must be a semantic version without path characters')
jar=root/'build/forge.jar'
if not jar.exists():raise SystemExit('Build and test first.')
with zipfile.ZipFile(jar) as package:
    if any('IntegrationTest' in name for name in package.namelist()):raise SystemExit('Test classes leaked into the runtime JAR')
paths=[root/name for name in ['README.md','LICENSE','THIRD_PARTY.md','CHANGELOG.md','CONTRIBUTING.md','SECURITY.md','.gitattributes','.gitignore','build.ps1','build.sh','run.ps1','run.sh']]
for directory in ['analysis','desktop','fixtures','tests','docs','scripts','.github']:
    paths.extend(p for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix not in {'.pyc','.sqlite'})
paths.append(jar)
target=root/'build'/('forge-'+args.version+'.zip')
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted(set(paths)):
        if path.is_symlink():raise SystemExit('Refusing to package symbolic links')
        bundle.write(path, 'forge/'+path.relative_to(root).as_posix())
with zipfile.ZipFile(target) as bundle:
    assert bundle.testzip() is None
digest=hashlib.sha256(target.read_bytes()).hexdigest()
(target.parent/'SHA256SUMS.txt').write_text(digest+'  '+target.name+'\n',encoding='utf-8')
print(target.name, target.stat().st_size, 'bytes; archive and runtime contents checked')
