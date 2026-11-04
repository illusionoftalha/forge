Push-Location $PSScriptRoot
try { java -jar build/forge.jar @args } finally { Pop-Location }
