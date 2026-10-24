package rtm.server.tests;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import RemoteTaskManager.ProcessStatus;

public final class TestManager {

    private final List<RemoteTest> tests;
    private final Map<String, RemoteTest> testsByName = new ConcurrentHashMap<>();
    private final Map<String, RemoteTest> testsById = new ConcurrentHashMap<>();
    private final Map<String, ProcessRecord> processes = new ConcurrentHashMap<>();
    private final ExecutorService monitorExecutor = Executors.newCachedThreadPool(runnable
            -> {
        Thread thread = new Thread(runnable, "test-process-monitor");
        thread.setDaemon(true);
        return thread;
    });

    public TestManager() {
        tests = TestCatalog.loadFromPlugins();
        for (RemoteTest test : tests) {
            testsByName.putIfAbsent(test.name().toLowerCase(), test);
            testsById.putIfAbsent(test.id().toLowerCase(), test);
        }

        System.out.println("Loaded test plugins: " + tests.size());
        for (RemoteTest test : tests) {
            System.out.println("  " + test.id() + " / " + test.name());
        }
    }

    public RemoteTaskManager.Process[] GetTests() {
        List<RemoteTaskManager.Process> result = new ArrayList<>();
        for (RemoteTest test : tests) {
            ProcessRecord latest = latestByTest(test.name());
            result.add(toCorba(test.id(), test.name(), latest));
        }
        return result.toArray(new RemoteTaskManager.Process[0]);
    }

    public RemoteTaskManager.Process[] GetProcesses() {
        List<ProcessRecord> records = new ArrayList<>(processes.values());
        records.sort(Comparator.comparing((ProcessRecord record) -> record.startedAt).reversed());
        List<RemoteTaskManager.Process> result = new ArrayList<>();
        for (ProcessRecord record : records) {
            result.add(toCorba(record.id, record.name, record));
        }
        return result.toArray(new RemoteTaskManager.Process[0]);
    }

    public synchronized ProcessStatus Run(RemoteTaskManager.Process processInfo) {
        String id = processInfo.id == null || processInfo.id.isBlank()
                ? UUID.randomUUID().toString()
                : processInfo.id;

        ProcessRecord existing = processes.get(id);
        if (existing != null
                && (existing.status.equals(ProcessStatus.RUNNING) || existing.status.equals(ProcessStatus.SCHEDULED))) {
            return existing.status;
        }

        RemoteTest test = findTest(processInfo.name);
        if (test == null) {
            test = findTest(processInfo.id);
        }
        if (test == null) {
            saveFailure(id, processInfo.name, "Test '" + processInfo.name + "' is not available on this server");
            return ProcessStatus.FAILED;
        }

        ProcessRecord record = new ProcessRecord(id, test.name());
        processes.put(id, record);

        try {
            ProcessLaunch launch = test.createLaunch();
            ProcessBuilder builder = new ProcessBuilder(launch.command());
            if (launch.workingDirectory() != null && !launch.workingDirectory().isBlank()) {
                builder.directory(new java.io.File(launch.workingDirectory()));
            }
            builder.environment().putAll(launch.environment());

            Process started = builder.start();
            record.pid = Math.toIntExact(started.pid());
            record.status = ProcessStatus.RUNNING;
            monitorExecutor.submit(() -> monitor(started, id, record.pid));
            return ProcessStatus.RUNNING;
        } catch (Exception e) {
            saveFailure(id, test.name(), e.getMessage());
            return ProcessStatus.FAILED;
        }
    }

    private RemoteTest findTest(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }

        String key = nameOrId.toLowerCase();
        RemoteTest test = testsByName.get(key);
        return test != null ? test : testsById.get(key);
    }

    private ProcessRecord latestByTest(String name) {
        return processes.values().stream()
                .filter(record -> record.name.equals(name))
                .max(Comparator.comparing(record -> record.startedAt))
                .orElse(null);
    }

    private void saveFailure(String id, String name, String error) {
        ProcessRecord record = new ProcessRecord(id, name == null ? "" : name);
        record.status = ProcessStatus.FAILED;
        record.finishedAt = Instant.now();
        record.error = error;
        processes.put(id, record);
    }

    private void monitor(Process process, String id, int pid) {
        try {
            int exitCode = process.waitFor();
            synchronized (this) {
                ProcessRecord record = processes.get(id);
                if (record == null || record.pid != pid) {
                    return;
                }

                record.status = exitCode == 0 ? ProcessStatus.STOPPED : ProcessStatus.FAILED;
                record.finishedAt = Instant.now();
                if (exitCode != 0) {
                    record.error = "Test exited with code " + exitCode;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static RemoteTaskManager.Process toCorba(String id, String name, ProcessRecord record) {
        RemoteTaskManager.Process process = new RemoteTaskManager.Process();
        process.id = id;
        process.name = name;
        process.pid = record == null ? 0 : record.pid;
        process.status = record == null ? ProcessStatus.UNKNOWN : record.status;
        return process;
    }

    private static final class ProcessRecord {

        private final String id;
        private final String name;
        private final Instant startedAt = Instant.now();
        private int pid;
        private ProcessStatus status = ProcessStatus.SCHEDULED;
        private Instant finishedAt;
        private String error;

        private ProcessRecord(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
