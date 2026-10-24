package rtm.server.tests;

import java.util.List;
import java.util.Map;

public record ProcessLaunch(List<String> command, String workingDirectory, Map<String, String> environment) {

    public ProcessLaunch {
        command = List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public ProcessLaunch(List<String> command) {
        this(command, null, Map.of());
    }
}
