@echo off
setlocal
set "ROOT=%~dp0"
set "DOTNET=dotnet"
set "PY=python"

echo [1/4] Preparing embedded Python runtime...
"%PY%" "%ROOT%packaging\prepare_runtime.py"
if errorlevel 1 goto :fail

echo [2/4] Publishing self-contained win-x64 app...
"%DOTNET%" publish "%ROOT%src\VSP.Desktop\VSP.Desktop.csproj" -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false --nologo -o "%ROOT%publish\win-x64"
if errorlevel 1 goto :fail

echo [3/4] Copying engine runtime into publish folder...
if not exist "%ROOT%publish\win-x64\runtime" mkdir "%ROOT%publish\win-x64\runtime"
xcopy /E /I /Y /Q "%ROOT%packaging\runtime\python" "%ROOT%publish\win-x64\runtime\python" >nul
if errorlevel 1 goto :fail

echo [4/4] Building MSI installer...
"%PY%" "%ROOT%packaging\make_installer.py"
if errorlevel 1 goto :fail

echo Done: %ROOT%dist\Toolbox-Setup-v1.1.3.msi
exit /b 0

:fail
echo Build failed.
exit /b 1
