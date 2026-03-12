@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

echo ========================================
echo   League Insight 优化打包脚本 (Native Image)
echo ========================================
echo.

set "ROOT_DIR=%~dp0"
set "BACKEND_DIR=%ROOT_DIR%league-insight-backend"
set "FRONTEND_DIR=%ROOT_DIR%league-insight-fronted"

:: 设置 GraalVM 路径
set "GRAALVM_HOME=C:\Users\ekko\Downloads\graalvm-jdk-21_windows-x64_bin\graalvm-jdk-21.0.10+8.1"
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%GRAALVM_HOME%\bin;%PATH%"

echo [1/4] 检查环境...

if not exist "%GRAALVM_HOME%\bin\java.exe" (
    echo 错误: 未找到 GraalVM，请设置正确的路径
    pause
    exit /b 1
)

where mvn >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未找到 Maven
    pause
    exit /b 1
)

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未找到 Node.js
    pause
    exit /b 1
)

echo ✓ 环境检查通过
echo.

echo [2/4] 编译 Native Image...
cd /d "%BACKEND_DIR%"

:: 初始化 MSVC 环境
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if %errorlevel% neq 0 (
    call "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
)
if %errorlevel% neq 0 (
    call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
)

:: 编译 Native Image
call mvn package -Pnative -DskipTests
if %errorlevel% neq 0 (
    echo 错误: Native Image 编译失败
    echo 请确保已安装:
    echo   1. GraalVM JDK 21
    echo   2. Visual Studio Build Tools (C++ 桌面开发)
    pause
    exit /b 1
)

if not exist "target\league-insight-native.exe" (
    echo 错误: 未找到 league-insight-native.exe
    pause
    exit /b 1
)

echo ✓ Native Image 编译完成
echo.

echo [3/4] 准备后端文件...
:: 复制 Native Image 到 dist 目录
set "OUTPUT_DIR=target\dist\league-insight"
rmdir /s /q "%OUTPUT_DIR%" 2>nul
mkdir "%OUTPUT_DIR%"
copy "target\league-insight-native.exe" "%OUTPUT_DIR%\league-insight.exe" >nul

echo ✓ 后端准备完成
echo.

echo [4/4] 构建前端...
cd /d "%FRONTEND_DIR%"

if not exist "node_modules" (
    echo 正在安装依赖...
    call npm install
)

call npm run electron:build
if %errorlevel% neq 0 (
    echo 错误: 前端构建失败
    pause
    exit /b 1
)

echo.
echo ========================================
echo   构建完成!
echo ========================================
echo.
echo 输出文件:
echo   - 安装包: %FRONTEND_DIR%\release\LeagueInsight Setup 1.0.0.exe
echo.
echo 预计体积: ~320MB (比 jpackage 方案减少约 200MB)
echo.

explorer "%FRONTEND_DIR%\release"
pause
