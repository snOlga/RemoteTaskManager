package rtm.server.tests.sample;

import rtm.server.tests.ProcessLaunch;
import rtm.server.tests.RemoteTest;

import java.util.List;

public final class JavaVersionTest implements RemoteTest
{
    @Override
    public String id()
    {
        return "java-version";
    }

    @Override
    public String name()
    {
        return "java -version";
    }

    @Override
    public ProcessLaunch createLaunch()
    {
        return new ProcessLaunch(List.of("java", "-version"));
    }
}
