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

rem --- 找 jar：开发布局在 target\，发布布局和本脚本同级 ---
set "JAR="
if exist "target\haruka.jar" set "JAR=target\haruka.jar"
if not defined JAR if exist "haruka.jar" set "JAR=haruka.jar"
if not defined JAR (
  echo [run] 找不到 haruka.jar。
  echo [run] 开发布局请先跑 build.bat；发布包请把 haruka.jar 和本脚本放在同一目录。
  exit /b 1
)

rem 用法: run.bat [配置文件路径] [--debug]
rem   不带参数就用当前目录的 config.properties
"%JAVA%" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%JAR%" %*
