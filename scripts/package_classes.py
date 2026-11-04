"""Create the runtime JAR without stale test classes from earlier builds."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
classes = root / 'build/classes'
entries = sorted(str(path.relative_to(classes)) for path in (classes / 'forge').rglob('*.class')
                 if not path.name.startswith('IntegrationTest'))
subprocess.run(['jar', '--create', '--file', str(root/'build/forge.jar'), '--main-class', 'forge.Main', *entries],cwd=classes,check=True)
