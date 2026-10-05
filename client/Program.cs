using DotNetOrb.Core;
using RemoteTaskManager;

using ServerName = string;

ORB orb = (ORB)ORB.Init();

Dictionary<ServerName, IMetricsServer> servers = new Dictionary<ServerName, IMetricsServer>(); // todo: db?
Console.WriteLine("Server amount: ");
string? serverAmountStr = Console.ReadLine();
int serverAmount = int.Parse(serverAmountStr == null ? "0" : serverAmountStr);
for (int i = 0; i < serverAmount; i++)
{
    string iorPath = "metricsServer" + i + ".ior";
    try
    {
        string ior = File.ReadAllText(iorPath);
        IMetricsServer metrics = MetricsServerHelper.Narrow(orb.StringToObject(ior));
        servers[metrics.getName()] = metrics;
    }
    catch (System.Exception)
    {
        Console.Error.Write("No such file or server with ior file " + iorPath);
    }
}
while (true)
{
    Console.WriteLine("all                  --  write every server names");
    Console.WriteLine("CPU [server_name]    --  get overall CPU usage (%)");
    Console.WriteLine("RAM [server_name]    --  get available RAM (GiB)");
    Console.WriteLine("DISK [server_name]   --  get total DISK space in GiB");
    Console.WriteLine("stop                 --  stop orb");
    string? command = Console.ReadLine();
    switch (command)
    {
        case "all":
            Console.WriteLine("Server names:");
            foreach (var server in servers.Keys)
            {
                Console.WriteLine(" - " + server);
            }
            break;
        case string cpuCommand when cpuCommand.Contains("CPU"):
            Console.WriteLine($"CPU usage: {servers[cpuCommand.Split(" ")[1]].getCurrentCpuUsage():N1}%");
            break;
        case string ramCommand when ramCommand.Contains("RAM"):
            double availableRamGiB = servers[ramCommand.Split(" ")[1]].getAvailableRAM() / 1024.0;
            Console.WriteLine($"Available RAM: {availableRamGiB:N2} GiB");
            break;
        case string diskCommand when diskCommand.Contains("DISK"):
            Console.WriteLine("DISK: " + servers[diskCommand.Split(" ")[1]].getTotalDisk() + " GiB");
            break;
        case "stop":
            Console.WriteLine("Shutdowning...");
            orb.Shutdown(false);
            break;
        default:
            Console.WriteLine("No such command");
            break;
    }
}
