using LiteDB;
using RemoteTaskManager;

namespace client.Storage;

public sealed class ClientProcessRepository : IDisposable
{
    private const string CollectionName = "processes";

    private readonly LiteDatabase database;
    private readonly ILiteCollection<ClientProcessRecord> processes;

    public ClientProcessRepository(string databasePath)
    {
        string fullPath = Path.GetFullPath(databasePath);
        string? directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        database = new LiteDatabase(fullPath);
        processes = database.GetCollection<ClientProcessRecord>(CollectionName);
        processes.EnsureIndex(record => record.ServerId);
        processes.EnsureIndex(record => record.UpdatedAt);
    }

    public ClientProcessRecord? Get(string id)
    {
        return processes.FindById(id);
    }

    public IReadOnlyList<ClientProcessRecord> GetByServer(string serverId)
    {
        return processes.Find(record => record.ServerId == serverId)
            .OrderByDescending(record => record.StartedAt)
            .ToList();
    }

    public void Save(ClientProcessRecord record)
    {
        record.UpdatedAt = DateTime.UtcNow;
        processes.Upsert(record);
    }

    public void Sync(string serverId, IEnumerable<Process> remoteProcesses)
    {
        foreach (Process remote in remoteProcesses)
        {
            ClientProcessRecord record = processes.FindById(remote.id) ?? new ClientProcessRecord
            {
                Id = remote.id,
                StartedAt = DateTime.UtcNow
            };

            record.ServerId = serverId;
            record.Name = remote.name;
            record.Pid = remote.pid;
            record.Status = remote.status;
            AddOrUpdate(record);
        }
    }

    private void AddOrUpdate(ClientProcessRecord record)
    {
        record.UpdatedAt = DateTime.UtcNow;
        processes.Upsert(record);
    }

    public void Dispose()
    {
        database.Dispose();
    }
}
