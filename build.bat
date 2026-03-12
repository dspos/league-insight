@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

echo ========================================
echo   League Insight 打包脚本 (Native Image)
echo ========================================
echo.

set "ROOT_DIR=%~dp0"
set "BACKEND_DIR=%ROOT_DIR%league-insight-backend"
set "FRONTEND_DIR=%ROOT_DIR%league-insight-fronted"

:: 设置 GraalVM 路径
set "GRAALVM_HOME=C:\Users\ekko\Downloads\graalvm-jdk-21_windows-x64_bin\graalvm-jdk-21.0.10+8.1"
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%GRAALVM_HOME%\bin;%PATH%"

echo [1/5] 检查环境...

if not exist "%GRAALVM_HOME%\bin\java.exe" (
    echo 错误: 未找到 GraalVM
    echo 请修改脚本中的 GRAALVM_HOME 路径
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

echo [2/5] 初始化 MSVC 环境...

:: 尝试不同的 VS 路径
if exist "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
) else if exist "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" (
    call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
) else if exist "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    call "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    call "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
) else (
    echo 警告: 未找到 MSVC 环境，尝试继续...
)

echo ✓ MSVC 环境已初始化
echo.

echo [3/5] 编译 Native Image...
cd /d "%BACKEND_DIR%"

call mvn clean package -Pnative -DskipTests
if %errorlevel% neq 0 (
    echo.
    echo 错误: Native Image 编译失败
    echo.
    echo 请确保已安装:
    echo   1. GraalVM JDK 21
    echo   2. Visual Studio Build Tools ^(C++ 桌面开发工作负载^)
    echo   3. 正确设置 GRAALVM_HOME 环境变量
    echo.
    pause
    exit /b 1
)

if not exist "target\league-insight-native.exe" (
    echo 错误: 未找到 league-insight-native.exe
    pause
    exit /b 1
)

for %%A in ("target\league-insight-native.exe") do set NATIVE_SIZE=%%~zA
echo ✓ Native Image 编译完成 (大小: %NATIVE_SIZE:~0,-6%MB)
echo.

echo [4/5] 构建前端...
cd /d "%FRONTEND_DIR%"

if not exist "node_modules" (
    echo 正在安装依赖...
    call npm install
    if %errorlevel% neq 0 (
        echo 错误: 依赖安装失败
        pause
        exit /b 1
    )
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
echo   - 免安装: %FRONTEND_DIR%\release\win-unpacked\
echo.
echo 特点:
echo   - Native Image 后端，无需 JVM
echo   - 预计总体积: ~320MB
echo.

explorer "%FRONTEND_DIR%\release"
pause
