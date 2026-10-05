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
    Console.WriteLine("RAM [server_name]    --  get available RAM");
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
        case string ramCommand when ramCommand.Contains("RAM"):
            Console.WriteLine("RAM: " + servers[ramCommand.Split(" ")[1]].getAvailableRAM());
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
