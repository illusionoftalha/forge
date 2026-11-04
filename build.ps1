$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force build/classes | Out-Null
    $sources = Get-ChildItem desktop/src -Recurse -Filter *.java | ForEach-Object FullName
    javac -encoding UTF-8 -d build/classes $sources tests/IntegrationTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Python tests failed' }
    java -cp build/classes forge.IntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
    jar --create --file build/forge.jar --main-class forge.Main -C build/classes forge
    if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
} finally { Pop-Location }
