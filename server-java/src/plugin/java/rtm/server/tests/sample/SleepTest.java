package rtm.server.tests.sample;

import rtm.server.tests.ProcessLaunch;
import rtm.server.tests.RemoteTest;

import java.util.List;

public final class SleepTest implements RemoteTest
{
    @Override
    public String id()
    {
        return "sleep-10";
    }

    @Override
    public String name()
    {
        return "Sleep 10 seconds";
    }

    @Override
    public ProcessLaunch createLaunch()
    {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win"))
        {
            return new ProcessLaunch(List.of("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 10"));
        }
        return new ProcessLaunch(List.of("/bin/sh", "-c", "sleep 10"));
    }
}
