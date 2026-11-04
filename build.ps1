$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force build/classes, build/test-classes | Out-Null
    $sources = Get-ChildItem desktop/src -Recurse -Filter *.java | ForEach-Object FullName
    javac -encoding UTF-8 -d build/classes $sources
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    javac -encoding UTF-8 -cp build/classes -d build/test-classes tests/IntegrationTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Python tests failed' }
    java -cp 'build/test-classes;build/classes' forge.IntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
    python scripts/package_classes.py
    if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
} finally { Pop-Location }
