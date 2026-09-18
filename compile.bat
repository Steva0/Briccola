@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   COMPILAZIONE AUTOMATICA BRICCOLA
echo ===================================================

:: Percorso della cartella radice del progetto (dove si trova questo script)
set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"

:: Controllo presenza dei file segreti (keystore e properties)
if not exist "keystore.properties" goto missing_secrets
if not exist "release.keystore" goto missing_secrets

echo [OK] File segreti trovati. Avvio della compilazione...
goto start_build

:missing_secrets
echo.
echo [ERRORE] File segreti mancanti!
echo Assicurati di inserire "keystore.properties" e "release.keystore" 
echo all'interno della cartella:
echo %PROJECT_DIR%
echo.
goto end

:start_build
echo [INFO] Compilazione Bundle (AAB) e Pacchetto (APK) in corso...

:: Esecuzione pulita di Gradle per bundle e apk di rilascio
call gradlew.bat bundleRelease assembleRelease
if %ERRORLEVEL% neq 0 goto build_error

:: Estrazione della versione dal file build.gradle.kts (cerca versionName)
set "VERSION=unknown"
for /f "tokens=2 delims==" %%a in ('findstr /C:"versionName" app\build.gradle.kts') do (
    set "VERSION=%%a"
)
:: Pulisce gli spazi o le virgolette intorno alla versione estratta
set "VERSION=%VERSION: =%"
set "VERSION=%VERSION:"=%"

echo [INFO] Versione rilevata: %VERSION%

:: Percorso del Desktop dell'utente corrente
set "DESKTOP_DIR=%USERPROFILE%\Desktop"

:: Copia e rinomina AAB
if exist "app\build\outputs\bundle\release\app-release.aab" (
    copy "app\build\outputs\bundle\release\app-release.aab" "%DESKTOP_DIR%\briccola_%VERSION%.aab" >nul
    echo [OK] File AAB copiato sul Desktop: briccola_%VERSION%.aab
) else (
    echo [AVVISO] File AAB non trovato nella cartella di output.
)

:: Copia e rinomina APK
if exist "app\build\outputs\apk\release\app-release.apk" (
    copy "app\build\outputs\apk\release\app-release.apk" "%DESKTOP_DIR%\briccola_%VERSION%.apk" >nul
    echo [OK] File APK copiato sul Desktop: briccola_%VERSION%.apk
) else (
    echo [AVVISO] File APK non trovato nella cartella di output.
)

echo.
echo ===================================================
echo   OPERAZIONE COMPLETATA CON SUCCESSO!
echo ===================================================
goto end

:build_error
echo.
echo [ERRORE] La compilazione e' fallita.
echo.

:end
echo ===================================================
echo   OPERAZIONE INTERROTTA
echo ===================================================
pause