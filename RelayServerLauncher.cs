using System;
using System.Diagnostics;
using System.IO;

namespace RelayServerLauncher
{
    class Program
    {
        static void Main(string[] args)
        {
            try
            {
                // 找到当前程序所在目录
                string appDir = AppDomain.CurrentDomain.BaseDirectory;
                
                // 检查 Java 是否安装
                Process javaCheck = new Process();
                javaCheck.StartInfo.FileName = "java";
                javaCheck.StartInfo.Arguments = "-version";
                javaCheck.StartInfo.RedirectStandardOutput = true;
                javaCheck.StartInfo.RedirectStandardError = true;
                javaCheck.StartInfo.UseShellExecute = false;
                javaCheck.StartInfo.CreateNoWindow = true;
                
                try
                {
                    javaCheck.Start();
                    javaCheck.WaitForExit();
                }
                catch
                {
                    Console.WriteLine("错误：未找到 Java，请先安装 Java 8 或更高版本！");
                    Console.WriteLine("按任意键退出...");
                    Console.ReadKey();
                    return;
                }
                
                // 查找 JAR 文件
                string jarPath = Path.Combine(appDir, "IPv6RelayServer-GUI.jar");
                if (!File.Exists(jarPath))
                {
                    jarPath = Path.Combine(appDir, "..", "IPv6RelayServer-GUI.jar");
                    if (!File.Exists(jarPath))
                    {
                        Console.WriteLine("错误：未找到 IPv6RelayServer-GUI.jar 文件！");
                        Console.WriteLine("按任意键退出...");
                        Console.ReadKey();
                        return;
                    }
                }
                
                Console.WriteLine("正在启动 IPv6 中继服务器 GUI 版...");
                Console.WriteLine();
                
                // 启动 JAR
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = "java",
                    Arguments = "-jar \"" + jarPath + "\"",
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                
                Process.Start(psi);
            }
            catch (Exception ex)
            {
                Console.WriteLine("错误：" + ex.Message);
                Console.WriteLine("按任意键退出...");
                Console.ReadKey();
            }
        }
    }
}
