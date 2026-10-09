using DotNetOrb.Core;
using RemoteTaskManager;

using ServerName = string;

ORB orb = (ORB)ORB.Init();

Dictionary<ServerName, IMetricsServer> servers = new Dictionary<ServerName, IMetricsServer>();
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
        servers[metrics.GetServerId()] = metrics;
    }
    catch (System.Exception e)
    {
        Console.Error.WriteLine("Failed to connect to ior file " + iorPath + ": " + e.Message);
    }
}

while (true)
{
    PrintUsage();
    string? command = Console.ReadLine();
    if (command == null)
    {
        continue;
    }

    string[] tokens = command.Split(' ', StringSplitOptions.RemoveEmptyEntries);
    string action = tokens.Length == 0 ? "" : tokens[0];
    string arg = tokens.Length > 1 ? command.Substring(action.Length).Trim() : "";

    try
    {
        switch (action)
        {
            case "all":
                servers.PrintAll();
                break;

            case "metrics":
                servers.PrintGetMetrics(arg);
                break;

            case "heartbeat":
                servers.PrintHeartbeat(arg);
                break;

            case "info":
                servers.PrintInfoFor(arg);
                break;

            case "stop":
                Console.WriteLine("Shutting down...");
                orb.Shutdown(false);
                return;

            default:
                Console.WriteLine("No such command");
                break;
        }
    }
    catch (KeyNotFoundException)
    {
        Console.Error.WriteLine("No server with id '" + arg + "'");
    }
    catch (System.Exception e)
    {
        Console.Error.WriteLine("Server '" + arg + "' is not responding: " + e.Message);
    }
}

static void PrintUsage()
{
    Console.WriteLine();
    Console.WriteLine("all                    --  write every server names");
    Console.WriteLine("metrics [server_name]  --  get CPU / memory / disk metrics");
    Console.WriteLine("heartbeat [server]     --  check whether the server is available");
    Console.WriteLine("info [server_name]     --  get server info (OS, CPU amount, memory, disk)");
    Console.WriteLine("stop                   --  stop orb");
}

public static class CustomExtensions
{
    extension(Dictionary<ServerName, IMetricsServer> servers)
    {
        public void PrintAll()
        {
            Console.WriteLine("Server names:");
            foreach (ServerName server in servers.Keys)
            {
                Console.WriteLine(" - " + server);
            }
        }

        public void PrintGetMetrics(string arg)
        {
            Metrics metrics = servers[arg].GetMetrics();
            Console.WriteLine("CPU usage:   " + metrics.cpuUsage.ToString("N1") + " %");
            Console.WriteLine("Memory used: " + (metrics.memoryUsage / 1024.0).ToString("N2") + " / " + (metrics.memoryTotal / 1024.0).ToString("N2") + " GiB");
            Console.WriteLine("Disk used:   " + metrics.diskUsage.ToString("N1") + " / " + metrics.diskTotal.ToString("N1") + " GiB");
        }

        public void PrintHeartbeat(string arg)
        {
            bool available = servers[arg].GetHeartbeat();
            Console.WriteLine("Server '" + arg + "' is " + (available ? "available" : "not available"));
        }

        public void PrintInfoFor(string arg)
        {
            ServerInfo info = servers[arg].GetServerInfo();
            Console.WriteLine("Server '" + info.name + "':");
            Console.WriteLine("  OS:           " + info.OS);
            Console.WriteLine("  CPU cores:    " + info.cpuAmount);
            Console.WriteLine("  Memory total: " + info.memoryTotal / 1024.0 + " GiB");
            Console.WriteLine("  Disk total:   " + info.diskTotal + " GiB");
        }
    }
}