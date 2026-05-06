@echo off
echo ========================================
echo   IPv6 中继服务器 (Python 版)
echo ========================================
echo.

REM 检查是否已存在 EXE
if exist "dist\IPv6中继服务器.exe" (
    echo 发现已打包好的 EXE，直接启动...
    start "" "dist\IPv6中继服务器.exe"
    exit /b 0
)

REM 运行 Python 脚本
python main.py

if errorlevel 1 (
    echo.
    echo 运行失败！请先安装依赖:
    echo   pip install -r requirements.txt
    echo.
    pause
)
