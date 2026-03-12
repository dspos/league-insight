@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ========================================
echo   Native Image 构建脚本
echo ========================================

:: 设置 GraalVM 路径
set GRAALVM_HOME=C:\Users\ekko\Downloads\graalvm-jdk-21_windows-x64_bin\graalvm-jdk-21.0.10+8.1
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

echo [93m检查 GraalVM...[0m
"%GRAALVM_HOME%\bin\java.exe" -version 2>&1
if errorlevel 1 (
    echo [91m错误: GraalVM 未找到[0m
    pause
    exit /b 1
)
echo [92m✓ GraalVM 已就绪[0m

echo.
echo [93m初始化 MSVC 环境...[0m
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if errorlevel 1 (
    echo [91m错误: 无法初始化 MSVC 环境[0m
    pause
    exit /b 1
)
echo [92m✓ MSVC 环境已就绪[0m

echo.
echo [93m开始 Native Image 编译 (这可能需要几分钟)...[0m
echo.

cd /d "%~dp0"
call mvn clean package -Pnative -DskipTests

if errorlevel 1 (
    echo.
    echo [91m错误: Native Image 编译失败[0m
    pause
    exit /b 1
)

echo.
echo [92m========================================[0m
echo [92m  编译成功！[0m
echo [92m  输出: target\league-insight-backend.exe[0m
echo [92m========================================[0m

pause
