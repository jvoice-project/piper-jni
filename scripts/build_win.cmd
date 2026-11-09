if not "%1"=="" set "AARCH=%1"
if "%AARCH%"=="" set "AARCH=x86-64"
if "%AARCH%"=="x86_64" set "AARCH=x86-64"
if "%AARCH%"=="amd64" set "AARCH=x86-64"
if "%AARCH%"=="arm64" set "AARCH=aarch64"

set "SOURCE_DIR=src\main\native"
set "BUILD_DIR=target\native-build"
set "TARGET_DIR=src\main\resources\native\windows-%AARCH%"

if not exist "src\main\native\piper\CMakeLists.txt" (
    git submodule update --init --recursive
)

cmake -S %SOURCE_DIR% ^
      -B %BUILD_DIR% ^
      -DCMAKE_INSTALL_PREFIX="%TARGET_DIR%" ^
      %2 %3 %4 %5 %6 %7 %8 %9
cmake --build %BUILD_DIR% --config Release -j %NUMBER_OF_PROCESSORS%
cmake --install %BUILD_DIR%

for /f "tokens=*" %%F in ('dir /b /s /a:L "%TARGET_DIR%\*.dll" 2^>nul') do (
    set "LINK=%%F"
    for /f "tokens=*" %%T in ('powershell -NoProfile -Command "(Get-Item '!LINK!').Target"') do set "TARGET=%%T"
    if defined TARGET if exist "!TARGET!" (
        for %%D in ("!TARGET!") do set "TARGET_PARENT=%%~dpD"
        del /f /q "!LINK!"
        move /y "!TARGET!" "!LINK!"
        rd "!TARGET_PARENT!" 2>nul
    )
)
