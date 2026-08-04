@echo off
setlocal enabledelayedexpansion

rem AI-EDT Plugin build script
rem Usage: build.cmd [EDT_INSTALL_DIR] [extra mvn args]
rem Example: build.cmd "C:\Program Files\1C\1CE\components\1c-edt-2025.2.3+30-x86_64"
rem Example: build.cmd "" -Dtycho.baseline.skip=true
rem
rem If EDT_INSTALL_DIR is empty or omitted, the script auto-detects it.
rem
rem Optional environment variables:
rem   MVN_HOME           - path to a Maven installation root (uses %MVN_HOME%\bin\mvn).
rem   MVN_EXEC           - explicit path to mvn or mvn.cmd executable.
rem   JAVA_HOME          - JDK 17 root. If not set the script looks under
rem                        "C:\Program Files\Zulu\zulu-17".
rem   EDT_MCP_SKIP_TESTS - set to 1 to skip the Tycho tests (default: they run, in a headless
rem                        OSGi runtime, and a red suite fails the build).

set "TARGET_FILE=%~dp0mcp\targets\default\default.target"
set "BACKUP_FILE=%~dp0mcp\targets\default\default.target.bak"
set "EDT_DIR=%~1"

rem Auto-detect EDT if not specified
if "%EDT_DIR%"=="" (
    for /d %%d in ("C:\Program Files\1C\1CE\components\1c-edt-*") do (
        set "EDT_DIR=%%d"
    )
)

if "%EDT_DIR%"=="" (
    echo ERROR: EDT installation not found.
    echo Usage: build.cmd "C:\path\to\1c-edt-directory"
    exit /b 1
)

if not exist "%EDT_DIR%\plugins" (
    echo ERROR: Invalid EDT directory: %EDT_DIR%
    echo The directory must contain a "plugins" subdirectory.
    exit /b 1
)

echo Using EDT: %EDT_DIR%

rem Auto-detect JAVA_HOME if not set
if "%JAVA_HOME%"=="" (
    if exist "C:\Program Files\Zulu\zulu-17" set "JAVA_HOME=C:\Program Files\Zulu\zulu-17"
)
if "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME not set and "C:\Program Files\Zulu\zulu-17" missing.
    echo Set JAVA_HOME to a JDK 17 root and retry.
    exit /b 1
)
echo Using JDK:  %JAVA_HOME%

rem Resolve mvn executable
if "%MVN_EXEC%"=="" (
    if not "%MVN_HOME%"=="" set "MVN_EXEC=%MVN_HOME%\bin\mvn"
)
if "%MVN_EXEC%"=="" (
    where mvn >nul 2>&1 && set "MVN_EXEC=mvn"
)
if "%MVN_EXEC%"=="" (
    echo ERROR: mvn not found in PATH and neither MVN_EXEC nor MVN_HOME is set.
    echo Set MVN_HOME ^(e.g. C:\apache-maven-3.9.9^) and retry.
    exit /b 1
)
echo Using mvn:  %MVN_EXEC%

rem Backup original target
copy /y "%TARGET_FILE%" "%BACKUP_FILE%" >nul

rem Insert Directory location before </locations>
powershell -NoProfile -Command "(Get-Content '%TARGET_FILE%' -Raw) -replace '</locations>', ('<!-- EDT Runtime (auto-added by build script) -->' + [Environment]::NewLine + '<location path=\"%EDT_DIR%\" type=\"Directory\"/>' + [Environment]::NewLine + [Environment]::NewLine + '</locations>') | Set-Content '%TARGET_FILE%' -NoNewline"

rem Tests run by default: tycho-surefire executes them in a headless OSGi runtime, so a red suite
rem fails the build. Set EDT_MCP_SKIP_TESTS=1 to skip them - only worth it for a quick packaging
rem check, because it hides every test.
set "SKIP_TESTS_ARG="
if "%EDT_MCP_SKIP_TESTS%"=="1" set "SKIP_TESTS_ARG=-DskipTests"

shift
set "EXTRA_MVN_ARGS="
:collect_args
if "%~1"=="" goto run_build
set "EXTRA_MVN_ARGS=%EXTRA_MVN_ARGS% %~1"
shift
goto collect_args

:run_build
echo Building (mvn clean verify %SKIP_TESTS_ARG%%EXTRA_MVN_ARGS%) ...
cd /d "%~dp0mcp"
call "%MVN_EXEC%" clean verify %SKIP_TESTS_ARG%%EXTRA_MVN_ARGS%
set "BUILD_RESULT=%ERRORLEVEL%"

rem Restore original target
copy /y "%BACKUP_FILE%" "%TARGET_FILE%" >nul
del "%BACKUP_FILE%" >nul 2>&1

if %BUILD_RESULT%==0 (
    echo.
    echo BUILD SUCCESS
    echo P2 repository: mcp\repositories\ru.aiedt.mcp.server.repository\target\repository\
    echo ZIP archive:    mcp\repositories\ru.aiedt.mcp.server.repository\target\*.zip
) else (
    echo.
    echo BUILD FAILED ^(exit %BUILD_RESULT%^)
)

endlocal & exit /b %BUILD_RESULT%
