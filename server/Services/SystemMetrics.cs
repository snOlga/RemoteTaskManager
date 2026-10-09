using System.Runtime.InteropServices;

namespace server.Services;

public static class SystemMetrics
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private sealed class MemoryStatusEx
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;

        public MemoryStatusEx()
        {
            dwLength = (uint)Marshal.SizeOf(typeof(MemoryStatusEx));
        }
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx([In, Out] MemoryStatusEx lpBuffer);

    public static (ulong totalBytes, ulong availableBytes) GetPhysicalMemory()
    {
        var status = new MemoryStatusEx();
        if (OperatingSystem.IsWindows() && GlobalMemoryStatusEx(status))
        {
            return (status.ullTotalPhys, status.ullAvailPhys);
        }
        return (0, 0);
    }
}