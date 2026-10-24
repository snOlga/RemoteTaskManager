using LiteDB;
using RemoteTaskManager;

namespace client.Storage;

public sealed class ClientProcessRecord
{
    [BsonId]
    public string Id { get; set; } = "";

    public string ServerId { get; set; } = "";

    public string Name { get; set; } = "";

    public int Pid { get; set; }

    public ProcessStatus Status { get; set; } = ProcessStatus.UNKNOWN;

    public DateTime StartedAt { get; set; } = DateTime.UtcNow;

    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    public string? Error { get; set; }
}
