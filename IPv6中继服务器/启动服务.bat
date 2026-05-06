@echo off
chcp 65001 >nul
title IPv6 中继服务器
echo ========================================
echo   IPv6 中继服务器 (GUI 版)
echo ========================================
echo.

cd /d "%~dp0"

if not exist "IPv6RelayServer-GUI.jar" (
    echo ❌ 错误：未找到 IPv6RelayServer-GUI.jar
    pause
    exit /b 1
)

echo 🚀 正在启动 IPv6 中继服务器...
echo.

start javaw -jar IPv6RelayServer-GUI.jar

echo ✅ 服务已启动！
timeout /t 2 >nul
