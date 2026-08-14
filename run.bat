@echo off
setlocal

for %%I in ("%~dp0.") do set "PROJECT_DIRECTORY=%%~fI"
if not defined RUNNER_IMAGE set "RUNNER_IMAGE=monitoring-bootstrap-runner:local"

where docker >nul 2>nul
if errorlevel 1 (
    echo ERROR: Docker must be installed and available in PATH. 1>&2
    exit /b 1
)

docker info >nul 2>nul
if errorlevel 1 (
    echo ERROR: Docker is not running or the current user cannot access it. 1>&2
    exit /b 1
)

echo Preparing the Node.js runner image...
docker build ^
    --file "%PROJECT_DIRECTORY%\runner\Dockerfile" ^
    --tag "%RUNNER_IMAGE%" ^
    "%PROJECT_DIRECTORY%\runner"
if errorlevel 1 exit /b %ERRORLEVEL%

docker run --rm ^
    --env DOCKER_HOST=unix:///var/run/docker.sock ^
    --env HOME=/tmp ^
    --mount "type=bind,source=%PROJECT_DIRECTORY%,target=/workspace" ^
    --mount "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock" ^
    --workdir /workspace ^
    "%RUNNER_IMAGE%" %*

exit /b %ERRORLEVEL%
