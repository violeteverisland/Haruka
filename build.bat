@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem --- Resolve a JDK: JAVA_HOME first, then common install locations ---
if not defined JAVA_HOME (
  for /d %%D in ("%USERPROFILE%\.jdks\*") do (
    if exist "%%~fD\bin\javac.exe" set "JAVA_HOME=%%~fD"
  )
)
if not defined JAVA_HOME (
  for /d %%D in ("%ProgramFiles%\Java\*" "%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Microsoft\jdk-*" "%ProgramFiles%\BellSoft\*") do (
    if exist "%%~fD\bin\javac.exe" set "JAVA_HOME=%%~fD"
  )
)
if not defined JAVA_HOME (
  echo [build] JAVA_HOME is not set and no known JDK was found.
  echo [build] Set JAVA_HOME to a JDK 17+ and run this again.
  exit /b 1
)
echo [build] JAVA_HOME=!JAVA_HOME!

rem --- Resolve Maven: PATH first, then the one bundled with IntelliJ / other JetBrains IDEs ---
where mvn >nul 2>nul
if %errorlevel%==0 (
  call mvn -q -DskipTests clean package
) else (
  set "MVN_FULL="
  for /d %%D in ("%ProgramFiles%\JetBrains\*" "%LOCALAPPDATA%\Programs\JetBrains\*" "%LOCALAPPDATA%\JetBrains\*") do (
    if exist "%%~fD\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" set "MVN_FULL=%%~fD\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
  )
  if not defined MVN_FULL (
    echo [build] Maven not found. Install Maven and add it to PATH, or use the one bundled with IntelliJ.
    exit /b 1
  )
  echo [build] Maven=!MVN_FULL!
  call "!MVN_FULL!" -q -DskipTests clean package
)

if errorlevel 1 (
  echo [build] FAILED
  exit /b 1
)
echo [build] OK: target\haruka.jar
