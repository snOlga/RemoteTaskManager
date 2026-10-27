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
                if (string.IsNullOrWhiteSpace(arg))
                {
                    Console.WriteLine("Shutting down...");
                    orb.Shutdown(false);
                    return;
                }
                servers.PrintStopProcess(arg, processRepository);
                break;

            default:
                Console.WriteLine("No such command");
                break;
        }
    }
    catch (KeyNotFoundException)
    {
        Console.Error.WriteLine("No server with id '" + arg + "'");
    }
    catch (RemoteError e)
    {
        Console.Error.WriteLine("Remote server error: " + e.message);
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
    Console.WriteLine("processes [server_name]      --  list top 10 running OS processes");
    Console.WriteLine("stop [server] [process]      --  gracefully stop a process (id, pid or name)");
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
            catch (RemoteError)
            {
                throw;
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
            Process[]? remoteProcesses = null;
            string source;
            try
            {
                if (servers.TryGetValue(arg, out IMetricsServer? server))
                {
                    remoteProcesses = server.GetProcesses() ?? Array.Empty<Process>();
                    repository.Sync(arg, remoteProcesses);
                    source = "top 10 from server OS";
                }
                else
                {
                    source = "local database, server is not connected";
                }
            }
            catch (RemoteError)
            {
                throw;
            }
            catch (Exception e)
            {
                source = "local database, server error: " + e.Message;
            }

            if (remoteProcesses != null)
            {
                Console.WriteLine("Processes on '" + arg + "' (" + source + "):");
                if (remoteProcesses.Length == 0)
                {
                    Console.WriteLine("  (none)");
                    return;
                }

                foreach (Process process in remoteProcesses)
                {
                    Console.WriteLine("  " + process.id + "  " + process.name + "  PID=" + process.pid + "  " + process.status);
                }
                return;
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

        public void PrintStopProcess(string arg, ClientProcessRepository repository)
        {
            string[] parts = arg.Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2)
            {
                Console.WriteLine("Usage: stop [server_name] [process id, pid or name]");
                return;
            }

            string serverName = parts[0];
            string processKey = parts[1];
            IMetricsServer server = servers[serverName];

            Process? target = null;
            try
            {
                Process[] remote = server.GetProcesses();
                target = remote.FirstOrDefault(item => item.id == processKey)
                    ?? remote.FirstOrDefault(item => item.pid > 0 && item.pid.ToString() == processKey)
                    ?? remote.FirstOrDefault(item => string.Equals(item.name, processKey, StringComparison.OrdinalIgnoreCase));
            }
            catch (RemoteError)
            {
                throw;
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("Failed to get processes from server, trying local database: " + e.Message);
            }

            if (target == null)
            {
                IReadOnlyList<ClientProcessRecord> local = repository.GetByServer(serverName);
                ClientProcessRecord? record = local.FirstOrDefault(item => item.Id == processKey)
                    ?? local.FirstOrDefault(item => item.Pid > 0 && item.Pid.ToString() == processKey)
                    ?? local.FirstOrDefault(item => string.Equals(item.Name, processKey, StringComparison.OrdinalIgnoreCase));

                if (record != null)
                {
                    target = new Process
                    {
                        id = record.Id,
                        name = record.Name,
                        pid = record.Pid,
                        status = record.Status
                    };
                }
            }

            if (target == null)
            {
                Console.WriteLine("Process '" + processKey + "' not found on '" + serverName + "'");
                return;
            }

            ProcessStatus status = server.Stop(target);
            Console.WriteLine("Process '" + target.id + "' on '" + serverName + "': " + status);

            ClientProcessRecord? stored = repository.Get(target.id);
            if (stored != null)
            {
                stored.Status = status;
                repository.Save(stored);
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
