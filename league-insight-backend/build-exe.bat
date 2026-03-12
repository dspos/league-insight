@echo off
chcp 65001 >nul
echo ========================================
echo  League Insight 打包脚本 (带 JVM)
echo ========================================
echo.

set APP_NAME=league-insight
set APP_VERSION=1.0.0
set MAIN_CLASS=com.ekko.insight.RankAnalysisApplication
set INPUT_DIR=target\jpackage-input
set OUTPUT_DIR=target\dist

echo [1/4] 清理旧文件...
call mvn clean package -DskipTests
if %ERRORLEVEL% neq 0 (
    echo Maven 打包失败!
    pause
    exit /b 1
)

echo [2/4] 准备打包目录...
rmdir /s /q %INPUT_DIR% 2>nul
mkdir %INPUT_DIR%\lib
mkdir %INPUT_DIR%\bin

REM 复制主 jar
copy target\league-insight-backend-%APP_VERSION%.jar %INPUT_DIR%\lib\

REM 复制依赖 jar (使用 maven-dependency-plugin)
call mvn dependency:copy-dependencies -DoutputDirectory=%INPUT_DIR%\lib -DincludeScope=runtime
if %ERRORLEVEL% neq 0 (
    echo 复制依赖失败!
    pause
    exit /b 1
)

echo [3/4] 使用 jpackage 打包...
rmdir /s /q %OUTPUT_DIR% 2>nul

jpackage ^
  --type app-image ^
  --name %APP_NAME% ^
  --app-version %APP_VERSION% ^
  --input %INPUT_DIR%\lib ^
  --main-jar league-insight-backend-%APP_VERSION%.jar ^
  --main-class %MAIN_CLASS% ^
  --dest %OUTPUT_DIR% ^
  --java-options "-Xms32m -Xmx256m" ^
  --java-options "--enable-native-access=ALL-UNNAMED" ^
  --win-console ^
  --win-dir-chooser

if %ERRORLEVEL% neq 0 (
    echo jpackage 打包失败!
    echo 请确保使用 JDK 21 并设置了 JAVA_HOME
    pause
    exit /b 1
)

echo [4/4] 打包完成!
echo.
echo 输出目录: %OUTPUTDIR%\%APP_NAME%
echo 可执行文件: %OUTPUT_DIR%\%APP_NAME%\%APP_NAME%.exe
echo.
pause
