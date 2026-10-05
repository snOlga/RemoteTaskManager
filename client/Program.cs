using DotNetOrb.Core;
using RemoteTaskManager;

ORB orb = (ORB)ORB.Init();

string iorPath = args.Length > 0 ? args[0] : "metricsServer.ior";
string ior = File.ReadAllText(iorPath);
IMetricsServer metrics = MetricsServerHelper.Narrow(orb.StringToObject(ior));

Console.WriteLine("-----------------------------");
Console.WriteLine("CPU: " + metrics.getCurrentCpuUsage());
Console.WriteLine("RAM: " + metrics.getAvailableRAM());
Console.WriteLine("Disk: " + metrics.getTotalDisk());

orb.Shutdown(false);