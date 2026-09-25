@echo off
setlocal EnableExtensions EnableDelayedExpansion

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

set "TEMPLATE_FILE=%PROJECT_DIRECTORY%\.env.template"
set "ENVIRONMENT_FILE=%PROJECT_DIRECTORY%\.env"
if not exist "!TEMPLATE_FILE!" (
    echo ERROR: .env.template was not found. 1>&2
    exit /b 1
)

if not exist "!ENVIRONMENT_FILE!" set "ENVIRONMENT_FILE=!TEMPLATE_FILE!"

for /f "usebackq tokens=1,* delims==" %%A in ("!ENVIRONMENT_FILE!") do (
    if /I "%%A"=="PUBLIC_PORT" set "PUBLIC_PORT=%%B"
    if /I "%%A"=="PUBLIC_HTTP_PORT" set "PUBLIC_HTTP_PORT=%%B"
    if /I "%%A"=="COMPOSE_PROJECT_NAME" set "ENV_COMPOSE_PROJECT_NAME=%%B"
)
if not defined PUBLIC_PORT (
    for /f "usebackq tokens=1,* delims==" %%A in ("!TEMPLATE_FILE!") do (
        if /I "%%A"=="PUBLIC_PORT" set "PUBLIC_PORT=%%B"
    )
)
if not defined ENV_COMPOSE_PROJECT_NAME (
    for /f "usebackq tokens=1,* delims==" %%A in ("!TEMPLATE_FILE!") do (
        if /I "%%A"=="COMPOSE_PROJECT_NAME" set "ENV_COMPOSE_PROJECT_NAME=%%B"
    )
)
if not defined PUBLIC_HTTP_PORT (
    for /f "usebackq tokens=1,* delims==" %%A in ("!TEMPLATE_FILE!") do (
        if /I "%%A"=="PUBLIC_HTTP_PORT" set "PUBLIC_HTTP_PORT=%%B"
    )
)

set "PUBLIC_PORT=!PUBLIC_PORT:"=!"
set "PUBLIC_PORT=!PUBLIC_PORT:'=!"
set "PUBLIC_HTTP_PORT=!PUBLIC_HTTP_PORT:"=!"
set "PUBLIC_HTTP_PORT=!PUBLIC_HTTP_PORT:'=!"
set "ENV_COMPOSE_PROJECT_NAME=!ENV_COMPOSE_PROJECT_NAME:"=!"
set "ENV_COMPOSE_PROJECT_NAME=!ENV_COMPOSE_PROJECT_NAME:'=!"

powershell -NoProfile -Command "$portValue = 0; if (-not [int]::TryParse('!PUBLIC_PORT!', [ref]$portValue) -or $portValue -lt 1 -or $portValue -gt 65535) { exit 1 }"
if errorlevel 1 (
    echo ERROR: PUBLIC_PORT must be an integer between 1 and 65535; received: !PUBLIC_PORT!. 1>&2
    exit /b 1
)
powershell -NoProfile -Command "$portValue = 0; if (-not [int]::TryParse('!PUBLIC_HTTP_PORT!', [ref]$portValue) -or $portValue -lt 1 -or $portValue -gt 65535) { exit 1 }"
if errorlevel 1 (
    echo ERROR: PUBLIC_HTTP_PORT must be an integer between 1 and 65535; received: !PUBLIC_HTTP_PORT!. 1>&2
    exit /b 1
)

set "EXPECTED_PUBLISHER=!ENV_COMPOSE_PROJECT_NAME!-publisher"
set "PUBLISHER_OWNS_PORT="
set "UNEXPECTED_CONTAINER="
for /f "delims=" %%C in ('docker ps --filter "publish=!PUBLIC_PORT!" --format "{{.Names}}"') do (
    if /I "%%C"=="!EXPECTED_PUBLISHER!" (
        set "PUBLISHER_OWNS_PORT=1"
    ) else (
        set "UNEXPECTED_CONTAINER=%%C"
    )
)
if defined UNEXPECTED_CONTAINER (
    echo ERROR: PUBLIC_PORT !PUBLIC_PORT! is already published by !UNEXPECTED_CONTAINER!. 1>&2
    exit /b 1
)

if defined PUBLISHER_OWNS_PORT (
    echo PUBLIC_PORT !PUBLIC_PORT! is already owned by !EXPECTED_PUBLISHER!; continuing.
) else (
    powershell -NoProfile -Command "try { $listener = (@(Get-NetTCPConnection -State Listen -ErrorAction Stop)).Where({ $_.LocalPort -eq !PUBLIC_PORT! }, 'First'); if (@($listener).Count -gt 0) { exit 1 }; exit 0 } catch { exit 2 }"
    set "PORT_CHECK_RESULT=!ERRORLEVEL!"
    if "!PORT_CHECK_RESULT!"=="1" (
        echo ERROR: PUBLIC_PORT !PUBLIC_PORT! is already in use. 1>&2
        exit /b 1
    )
    if not "!PORT_CHECK_RESULT!"=="0" (
        echo ERROR: PUBLIC_PORT !PUBLIC_PORT! could not be checked with Get-NetTCPConnection. 1>&2
        exit /b 1
    )
    echo PUBLIC_PORT !PUBLIC_PORT! is available.
)

if not "!PUBLIC_HTTP_PORT!"=="!PUBLIC_PORT!" (
    set "PUBLISHER_OWNS_HTTP_PORT="
    set "UNEXPECTED_HTTP_CONTAINER="
    for /f "delims=" %%C in ('docker ps --filter "publish=!PUBLIC_HTTP_PORT!" --format "{{.Names}}"') do (
        if /I "%%C"=="!EXPECTED_PUBLISHER!" (
            set "PUBLISHER_OWNS_HTTP_PORT=1"
        ) else (
            set "UNEXPECTED_HTTP_CONTAINER=%%C"
        )
    )
    if defined UNEXPECTED_HTTP_CONTAINER (
        echo ERROR: PUBLIC_HTTP_PORT !PUBLIC_HTTP_PORT! is already published by !UNEXPECTED_HTTP_CONTAINER!. 1>&2
        exit /b 1
    )
    if defined PUBLISHER_OWNS_HTTP_PORT (
        echo PUBLIC_HTTP_PORT !PUBLIC_HTTP_PORT! is already owned by !EXPECTED_PUBLISHER!; continuing.
    ) else (
        powershell -NoProfile -Command "try { $listener = (@(Get-NetTCPConnection -State Listen -ErrorAction Stop)).Where({ $_.LocalPort -eq !PUBLIC_HTTP_PORT! }, 'First'); if (@($listener).Count -gt 0) { exit 1 }; exit 0 } catch { exit 2 }"
        set "HTTP_PORT_CHECK_RESULT=!ERRORLEVEL!"
        if "!HTTP_PORT_CHECK_RESULT!"=="1" (
            echo ERROR: PUBLIC_HTTP_PORT !PUBLIC_HTTP_PORT! is already in use. 1>&2
            exit /b 1
        )
        if not "!HTTP_PORT_CHECK_RESULT!"=="0" (
            echo ERROR: PUBLIC_HTTP_PORT !PUBLIC_HTTP_PORT! could not be checked with Get-NetTCPConnection. 1>&2
            exit /b 1
        )
        echo PUBLIC_HTTP_PORT !PUBLIC_HTTP_PORT! is available.
    )
)

set "HOST_IS_INTERACTIVE="
powershell -NoProfile -Command "if ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected) { exit 1 } else { exit 0 }"
if not errorlevel 1 set "HOST_IS_INTERACTIVE=1"

set "NON_INTERACTIVE_REQUESTED="
echo(%*| findstr /C:"--non-interactive" >nul
if not errorlevel 1 set "NON_INTERACTIVE_REQUESTED=1"

set "REPLACE_STALE_RUNNER="
echo(%*| findstr /C:"--replace-stale-runner" >nul
if not errorlevel 1 set "REPLACE_STALE_RUNNER=1"

set "DOCKER_TTY_FLAGS="
if defined HOST_IS_INTERACTIVE if not defined NON_INTERACTIVE_REQUESTED set "DOCKER_TTY_FLAGS=-it"

set "STALE_RUNNER_FOUND="
for /f "delims=" %%N in ('docker ps -a --filter "name=^/monitoring-bootstrap-runner$" --format "{{.Names}}" 2^>nul') do set "STALE_RUNNER_FOUND=1"

if defined STALE_RUNNER_FOUND (
    if defined REPLACE_STALE_RUNNER (
        echo A previous monitoring-bootstrap-runner container was found; removing it because --replace-stale-runner was passed.
        docker rm -f monitoring-bootstrap-runner >nul 2>nul
    ) else if defined HOST_IS_INTERACTIVE (
        echo.
        echo WARNING: a monitoring-bootstrap-runner container from a previous run already exists.
        echo This usually happens when a previous window was closed while the interactive menu was waiting for input.
        choice /C SN /N /M "Remove it and continue (S), or abort (N)? "
        if errorlevel 2 (
            echo Aborted; the existing container was left untouched. 1>&2
            exit /b 1
        )
        docker rm -f monitoring-bootstrap-runner >nul 2>nul
    ) else (
        echo ERROR: a monitoring-bootstrap-runner container from a previous run already exists, and this session cannot prompt for confirmation. 1>&2
        echo Remove it manually with "docker rm -f monitoring-bootstrap-runner", or re-run with --replace-stale-runner to remove it automatically. 1>&2
        exit /b 1
    )
)

echo Preparing the Node.js runner image...
docker build ^
    --file "%PROJECT_DIRECTORY%\runner\Dockerfile" ^
    --tag "%RUNNER_IMAGE%" ^
    "%PROJECT_DIRECTORY%\runner"
if errorlevel 1 exit /b %ERRORLEVEL%

docker run --rm %DOCKER_TTY_FLAGS% ^
    --name monitoring-bootstrap-runner ^
    --env DOCKER_HOST=unix:///var/run/docker.sock ^
    --env HOME=/tmp ^
    --mount "type=bind,source=%PROJECT_DIRECTORY%,target=/workspace" ^
    --mount "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock" ^
    --workdir /workspace ^
    "%RUNNER_IMAGE%" %*

set "RUN_EXIT_CODE=%ERRORLEVEL%"
if not "%RUN_EXIT_CODE%"=="0" if defined HOST_IS_INTERACTIVE (
    echo.
    echo ERROR: run.bat finished with exit code %RUN_EXIT_CODE%. 1>&2
    pause
)
exit /b %RUN_EXIT_CODE%
