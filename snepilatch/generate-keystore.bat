@echo off
echo ========================================
echo Snepilatch Android Keystore Generator
echo ========================================
echo.

cd android

echo You need to provide passwords for your keystore:
set /p STORE_PASS="Enter keystore password (min 6 chars): "
set /p KEY_PASS="Enter key password (min 6 chars): "

echo.
echo Creating release keystore with predefined values...
echo.

REM Try to find keytool in common locations
if exist "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" (
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkey -v -keystore snepilatch-release.keystore -alias snepilatch -keyalg RSA -keysize 2048 -validity 10000 ^
        -dname "CN=Snepilatch App, OU=Development, O=Snepilatch, L=City, S=State, C=CH" ^
        -storepass "%STORE_PASS%" -keypass "%KEY_PASS%" -noprompt
) else if exist "%JAVA_HOME%\bin\keytool.exe" (
    "%JAVA_HOME%\bin\keytool.exe" -genkey -v -keystore snepilatch-release.keystore -alias snepilatch -keyalg RSA -keysize 2048 -validity 10000 ^
        -dname "CN=Snepilatch App, OU=Development, O=Snepilatch, L=City, S=State, C=CH" ^
        -storepass "%STORE_PASS%" -keypass "%KEY_PASS%" -noprompt
) else (
    echo ERROR: keytool not found!
    echo Please install Java JDK or Android Studio.
    pause
    exit /b 1
)

echo.
echo Creating key.properties file...
echo storePassword=%STORE_PASS%> key.properties
echo keyPassword=%KEY_PASS%>> key.properties
echo keyAlias=snepilatch>> key.properties
echo storeFile=snepilatch-release.keystore>> key.properties

echo.
echo ========================================
echo SUCCESS! Keystore created automatically!
echo ========================================
echo.
echo Now run this PowerShell command to copy base64 for GitHub:
echo.
echo    [Convert]::ToBase64String([IO.File]::ReadAllBytes("android\snepilatch-release.keystore")) ^| clip
echo.
echo This will copy the base64 string to your clipboard.
echo Then add it as ANDROID_KEYSTORE_BASE64 secret in GitHub.
echo.
pause