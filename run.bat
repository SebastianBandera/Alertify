@echo off
setlocal

where node >nul 2>nul
if errorlevel 1 (
    echo ERROR: Node.js 18 o posterior debe estar instalado y disponible en PATH. 1>&2
    exit /b 1
)

node "%~dp0run.js" %*
exit /b %ERRORLEVEL%
