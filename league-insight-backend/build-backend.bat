@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

echo ========================================
echo   League Insight 打包脚本 (Native Image)
echo ========================================
echo.

:: 记录开始时间
set "START_TIME=%time%"

set "ROOT_DIR=%~dp0"
set "BACKEND_DIR=%ROOT_DIR%league-insight-backend"
set "FRONTEND_DIR=%ROOT_DIR%league-insight-fronted"

:: 设置 GraalVM 路径
set "GRAALVM_HOME=C:\develop\graalvm-jdk-21.0.10+8.1"
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

echo    GraalVM: %GRAALVM_HOME%
echo    Maven: OK
echo    Node.js: OK
echo ✓ 环境检查通过
echo.

echo [2/5] 初始化 MSVC 环境...

set "VCVARS_FOUND=0"

:: 使用 vswhere 自动查找 VS 安装路径 (推荐方式)
if exist "%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" (
    for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
        if exist "%%i\VC\Auxiliary\Build\vcvars64.bat" (
            call "%%i\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
            set "VCVARS_FOUND=1"
            echo    使用 VS: %%i
        )
    )
)

:: 如果 vswhere 未找到，尝试手动路径
if exist "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
    set "VCVARS_FOUND=1"
    echo    使用 VS: %%i
)  else (
    echo 警告: 未找到 MSVC 环境，尝试继续...
)



if "%VCVARS_FOUND%"=="0" (
    echo 警告: 未找到 MSVC 环境，Native Image 编译可能失败
    echo 请安装 Visual Studio Build Tools ^(C++ 桌面开发工作负载^)
) else (
    echo ✓ MSVC 环境已初始化
)
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

:: 正确计算文件大小 (MB)
for %%A in ("target\league-insight-native.exe") do set "NATIVE_SIZE_BYTES=%%~zA"
set /a "NATIVE_SIZE_MB=%NATIVE_SIZE_BYTES%/1024/1024"
echo ✓ Native Image 编译完成 (大小: %NATIVE_SIZE_MB% MB)
echo.