# Local development backend with the test operator (test money).
# Uses the database from .env (Atlas). Pass -LocalDb to use a local MongoDB instead.
# Then open http://localhost:8081/mock-operator/

param([switch]$LocalDb)

$ErrorActionPreference = "Stop"

if ($LocalDb) {
    $env:SPRING_DATA_MONGODB_URI = "mongodb://localhost:27017/rummy_local"
    $env:MONGODB_URI = "mongodb://localhost:27017/rummy_local"
    $env:RUMMY_MONGO_TRANSACTIONS = "false"
} else {
    Remove-Item Env:SPRING_DATA_MONGODB_URI, Env:MONGODB_URI, Env:RUMMY_MONGO_TRANSACTIONS -ErrorAction SilentlyContinue
}
$env:RUMMY_MOCK_OPERATOR_ENABLED = "true"
$env:RUMMY_GAME_URL = "http://localhost:5173"
$env:RUMMY_ALLOWED_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173"
$env:RUMMY_REDIS_ENABLED = "false"
$env:PORT = "8081"
Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue

Set-Location $PSScriptRoot
.\mvnw.cmd -q -DskipTests install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
.\mvnw.cmd -pl game-service spring-boot:run
