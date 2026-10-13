@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem --- Resolve a JDK: JAVA_HOME first, then common install locations ---
if not defined JAVA_HOME (
  for /d %%D in ("%USERPROFILE%\.jdks\*") do (
    if exist "%%~fD\bin\java.exe" set "JAVA_HOME=%%~fD"
  )
)
if not defined JAVA_HOME (
  for /d %%D in ("%ProgramFiles%\Java\*" "%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Microsoft\jdk-*" "%ProgramFiles%\BellSoft\*") do (
    if exist "%%~fD\bin\java.exe" set "JAVA_HOME=%%~fD"
  )
)

set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"

if not exist "target\haruka.jar" (
  echo Not built yet. Run build.bat first.
  exit /b 1
)

rem 用法: run.bat [配置文件路径] [--debug]
rem   不带参数就用当前目录的 config.properties
"%JAVA%" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar target\haruka.jar %*
