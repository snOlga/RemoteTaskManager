using client.Storage;
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

string clientDbPath = Path.Combine(AppContext.BaseDirectory, "rtm-client.db");
using var processRepository = new ClientProcessRepository(clientDbPath);
Console.WriteLine("Client process database: " + Path.GetFullPath(clientDbPath));

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

            case "tests":
                servers.PrintTests(arg);
                break;

            case "run":
                servers.PrintRunTest(arg, processRepository);
                break;

            case "processes":
                servers.PrintProcesses(arg, processRepository);
                break;

            case "average":
            case "avg":
                servers.PrintAverageMetrics(arg);
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
    Console.WriteLine("all                          --  write every server names");
    Console.WriteLine("metrics [server_name]        --  get CPU / memory / disk metrics");
    Console.WriteLine("average [server_name] [sec]  --  average metrics for the last N seconds");
    Console.WriteLine("heartbeat [server]           --  check whether the server is available");
    Console.WriteLine("info [server_name]           --  get server info (OS, CPU amount, memory, disk)");
    Console.WriteLine("tests [server_name]          --  list tests available on the server");
    Console.WriteLine("run [server_name] [test]     --  start a test (test name or id)");
    Console.WriteLine("processes [server_name]      --  list persisted process instances");
    Console.WriteLine("stop                         --  stop orb");
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

        public void PrintTests(string arg)
        {
            Process[] tests = servers[arg].GetTests();
            Console.WriteLine("Tests on '" + arg + "':");
            if (tests.Length == 0)
            {
                Console.WriteLine("  (none)");
                return;
            }

            foreach (Process test in tests.OrderBy(test => test.name, StringComparer.OrdinalIgnoreCase))
            {
                Console.WriteLine("  " + test.id.PadRight(20) + " " + test.name + " [" + test.status + "]");
            }
        }

        public void PrintRunTest(string arg, ClientProcessRepository repository)
        {
            string[] parts = arg.Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2)
            {
                Console.WriteLine("Usage: run [server_name] [test name or id]");
                return;
            }

            string serverName = parts[0];
            var process = new Process
            {
                id = Guid.NewGuid().ToString("N"),
                name = parts[1],
                pid = 0,
                status = ProcessStatus.SCHEDULED
            };

            ProcessStatus status = servers[serverName].Run(process);
            Console.WriteLine("Test '" + process.name + "' on '" + serverName + "': " + status);
            Console.WriteLine("Process id: " + process.id);

            Process? started = null;
            try
            {
                Process[] remote = servers[serverName].GetProcesses();
                repository.Sync(serverName, remote);
                started = remote.FirstOrDefault(item => item.id == process.id);
            }
            catch (Exception e)
            {
                repository.Save(new ClientProcessRecord
                {
                    Id = process.id,
                    ServerId = serverName,
                    Name = process.name,
                    Status = status,
                    StartedAt = DateTime.UtcNow,
                    Error = e.Message
                });
                Console.Error.WriteLine("Failed to refresh process from server, saved locally: " + e.Message);
            }

            if (started != null)
            {
                Console.WriteLine("PID: " + started.pid);
            }
        }

        public void PrintProcesses(string arg, ClientProcessRepository repository)
        {
            string source;
            try
            {
                if (servers.TryGetValue(arg, out IMetricsServer? server))
                {
                    repository.Sync(arg, server.GetProcesses());
                    source = "synced with server";
                }
                else
                {
                    source = "local database, server is not connected";
                }
            }
            catch (Exception e)
            {
                source = "local database, server error: " + e.Message;
            }

            IReadOnlyList<ClientProcessRecord> processes = repository.GetByServer(arg);
            Console.WriteLine("Processes on '" + arg + "' (" + source + "):");
            if (processes.Count == 0)
            {
                Console.WriteLine("  (none)");
                return;
            }

            foreach (ClientProcessRecord process in processes)
            {
                Console.WriteLine("  " + process.Id + "  " + process.Name + "  PID=" + process.Pid + "  " + process.Status
                    + "  updated=" + process.UpdatedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss"));
            }
        }

        public void PrintAverageMetrics(string arg)
        {
            string[] parts = arg.Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0)
            {
                Console.WriteLine("Usage: average [server_name] [seconds]");
                return;
            }

            int seconds = 60;
            if (parts.Length > 1 && !int.TryParse(parts[1], out seconds))
            {
                Console.WriteLine("Seconds must be an integer");
                return;
            }

            Metrics metrics = servers[parts[0]].GetAverageMetrics(new Duration { seconds = seconds });
            Console.WriteLine("Average metrics for the last " + seconds + " seconds on '" + parts[0] + "':");
            Console.WriteLine("CPU usage:   " + metrics.cpuUsage.ToString("N1") + " %");
            Console.WriteLine("Memory used: " + (metrics.memoryUsage / 1024.0).ToString("N2") + " / " + (metrics.memoryTotal / 1024.0).ToString("N2") + " GiB");
            Console.WriteLine("Disk used:   " + metrics.diskUsage.ToString("N1") + " / " + metrics.diskTotal.ToString("N1") + " GiB");
        }
    }
}