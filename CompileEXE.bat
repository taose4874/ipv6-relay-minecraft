@echo off
echo ========================================
echo   正在编译 EXE 启动器...
echo ========================================
echo.

set CSC_PATH=

REM 查找 C# 编译器
if exist "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe" (
    set CSC_PATH=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe
) else if exist "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe" (
    set CSC_PATH=C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe
)

if "%CSC_PATH%"=="" (
    echo ❌ 没有找到 C# 编译器！
    echo.
    echo 不过没关系！你可以：
    echo 1. 直接双击 IPv6RelayServer-GUI.jar 使用
    echo 2. 使用 启动服务.bat 启动
    echo 3. 或者下载 Launch4j 工具转成 EXE
    echo.
    pause
    exit /b 1
)

echo 找到 C# 编译器: %CSC_PATH%
echo.
echo 正在编译...
"%CSC_PATH%" /target:winexe /out:IPv6中继服务器.exe SimpleLauncher.cs

if exist "IPv6中继服务器.exe" (
    echo.
    echo ✅ EXE 编译成功！
    echo.
    echo 文件: IPv6中继服务器.exe
    echo.
    echo 现在可以直接双击运行了！
) else (
    echo ❌ 编译失败！
)

pause
