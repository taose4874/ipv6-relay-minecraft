using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

namespace SimpleLauncher
{
    class Program
    {
        static void Main(string[] args)
        {
            try
            {
                string appDir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
                
                // 检查 Java
                try
                {
                    Process javaTest = new Process();
                    javaTest.StartInfo.FileName = "java";
                    javaTest.StartInfo.Arguments = "-version";
                    javaTest.StartInfo.UseShellExecute = false;
                    javaTest.StartInfo.CreateNoWindow = true;
                    javaTest.Start();
                    javaTest.WaitForExit(2000);
                }
                catch
                {
                    MessageBox.Show("未找到 Java，请先安装 Java 8 或更高版本！", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return;
                }
                
                // 查找 JAR
                string jarPath = Path.Combine(appDir, "IPv6RelayServer-GUI.jar");
                if (!File.Exists(jarPath))
                {
                    MessageBox.Show("未找到 IPv6RelayServer-GUI.jar 文件！", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return;
                }
                
                // 启动
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = "javaw";
                psi.Arguments = "-jar \"" + jarPath + "\"";
                psi.WorkingDirectory = appDir;
                Process.Start(psi);
            }
            catch (Exception ex)
            {
                MessageBox.Show("启动失败: " + ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }
}
