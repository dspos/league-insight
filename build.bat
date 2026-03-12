@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

echo ========================================
echo   League Insight 构建脚本
echo ========================================
echo.

:: 设置颜色
for /f %%i in ('echo prompt $E^| cmd') do set "ESC=%%i"
set "GREEN=[92m"
set "YELLOW=[93m"
set "RED=[91m"
set "RESET=[0m"

:: 获取脚本所在目录
set "ROOT_DIR=%~dp0"
set "BACKEND_DIR=%ROOT_DIR%league-insight-backend"
set "FRONTEND_DIR=%ROOT_DIR%league-insight-fronted"

:: 设置 GraalVM 路径
set "GRAALVM_HOME=C:\Users\ekko\Downloads\graalvm-jdk-21_windows-x64_bin\graalvm-jdk-21.0.10+8.1"
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%GRAALVM_HOME%\bin;%PATH%"

echo %YELLOW%[1/4] 检查环境...%RESET%

:: 检查 GraalVM
if not exist "%GRAALVM_HOME%\bin\java.exe" (
    echo %RED%错误: 未找到 GraalVM，请检查路径%RESET%
    pause
    exit /b 1
)

:: 检查 Maven
where mvn >nul 2>&1
if %errorlevel% neq 0 (
    echo %RED%错误: 未找到 Maven，请先安装 Maven 并配置环境变量%RESET%
    pause
    exit /b 1
)

:: 检查 Node.js
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo %RED%错误: 未找到 Node.js，请先安装 Node.js%RESET%
    pause
    exit /b 1
)

echo %GREEN%✓ 环境检查通过%RESET%
echo.

echo %YELLOW%[2/4] 初始化 MSVC 环境...%RESET%
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if %errorlevel% neq 0 (
    echo %RED%错误: 无法初始化 MSVC 环境%RESET%
    pause
    exit /b 1
)
echo %GREEN%✓ MSVC 环境已就绪%RESET%
echo.

echo %YELLOW%[3/4] 构建 Native Image...%RESET%
cd /d "%BACKEND_DIR%"

:: Maven Native 编译
call mvn package -Pnative -DskipTests
if %errorlevel% neq 0 (
    echo %RED%错误: Native Image 构建失败%RESET%
    pause
    exit /b 1
)

:: 检查输出文件
if not exist "target\league-insight-native.exe" (
    echo %RED%错误: 未找到 league-insight-native.exe%RESET%
    pause
    exit /b 1
)

echo %GREEN%✓ Native Image 构建完成%RESET%
echo.

echo %YELLOW%[4/4] 构建前端...%RESET%
cd /d "%FRONTEND_DIR%"

:: 检查 node_modules
if not exist "node_modules" (
    echo 正在安装依赖...
    call npm install
    if %errorlevel% neq 0 (
        echo %RED%错误: 依赖安装失败%RESET%
        pause
        exit /b 1
    )
)

:: 构建 Electron 应用
call npm run electron:build
if %errorlevel% neq 0 (
    echo %RED%错误: 前端构建失败%RESET%
    pause
    exit /b 1
)

echo.
echo ========================================
echo   构建完成!
echo ========================================
echo.
echo %GREEN%输出文件:%RESET%
echo   - 安装包: %FRONTEND_DIR%\release\LeagueInsight Setup 1.0.0.exe
echo   - 免安装: %FRONTEND_DIR%\release\win-unpacked\
echo.

:: 打开输出目录
explorer "%FRONTEND_DIR%\release"

pause
