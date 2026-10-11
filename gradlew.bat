@rem ##########################################################################
@rem Gradle startup script for Windows
@rem ##########################################################################
@if "%DEBUG%"=="" @echo off
set GRADLE_APP_HOME=%~dp0
set JAVA_HOME=C:\Users\Flash Technology\Desktop\loop\.jdk17\jdk-17.0.20+8
set ANDROID_HOME=C:\Users\Flash Technology\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%PATH%
set "GRADLE_OPTS=%GRADLE_OPTS% "-Dfile.encoding=UTF8""
"C:\Users\Flash Technology\.gradle\wrapper\dists\gradle-8.2-bin\bbg7u40eoinfdyxsxr3z4i7ta\gradle-8.2\bin\gradle.bat" %*
