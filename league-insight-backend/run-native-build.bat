@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set JAVA_HOME=C:\Users\ekko\Downloads\graalvm-jdk-21_windows-x64_bin\graalvm-jdk-21.0.10+8.1
set PATH=%JAVA_HOME%\bin;%PATH%
java -version
mvn package -Pnative -DskipTests
