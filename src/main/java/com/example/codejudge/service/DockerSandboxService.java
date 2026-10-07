package com.example.codejudge.service;

import com.example.codejudge.entity.Verdict;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.*;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerSandboxService {

    private final DockerClient dockerClient;

    @Getter
    @Builder
    public static class ExecutionResult {
        private final Verdict verdict;
        private final String stdout;
        private final String stderr;
        private final int exitCode;
        private final long executionTimeMs;
    }

    public ExecutionResult execute(
            String imageTag,
            String[] runCommand,
            String hostSourceDirPath,
            String standardInput,
            long timeoutMillis
    ) {
        String containerId = null;
        long startTime = System.currentTimeMillis();

        try {
            // 1. Hardware, Network, and Security Constraints
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(256L * 1024 * 1024)                    // 256MB RAM Limit
                    .withMemorySwap(256L * 1024 * 1024)                // Disable Swap
                    .withNanoCPUs((long) (0.5 * 1_000_000_000L))       // 0.5 CPU Cores
                    .withPidsLimit(64L)                                // Mitigate fork bombs
                    .withNetworkMode("none")                           // Air-gapped: Disable network
                    .withReadonlyRootfs(true)                          // Read-only root filesystem
                    .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m")) // Ephemeral writable mount
                    .withBinds(new Bind(
                            hostSourceDirPath,
                            new Volume("/app"),
                            AccessMode.ro                              // Read-only host directory bind
                    ))
                    .withCapDrop(Capability.ALL);                      // Drop all kernel privileges

            // 2. Create Unprivileged Ephemeral Container
            CreateContainerResponse container = dockerClient.createContainerCmd(imageTag)
                    .withHostConfig(hostConfig)
                    .withWorkingDir("/app")
                    .withUser("1001:1001")                             // Non-root runner
                    .withCmd(runCommand)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withStdinOpen(true)
                    .withTty(false)
                    .exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();

            // 3. Attach I/O streams and pipe standardInput
            ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

            ResultCallback.Adapter<Frame> attachCallback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        if (frame.getStreamType() == StreamType.STDOUT) {
                            stdoutStream.write(frame.getPayload());
                        } else if (frame.getStreamType() == StreamType.STDERR) {
                            stderrStream.write(frame.getPayload());
                        }
                    } catch (IOException ignored) {}
                }
            };

            var attachCmd = dockerClient.attachContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true);

            if (standardInput != null && !standardInput.isEmpty()) {
                attachCmd.withStdIn(new ByteArrayInputStream(standardInput.getBytes(StandardCharsets.UTF_8)));
            }

            attachCmd.exec(attachCallback);

            // 4. Wait for execution with hard timeout (e.g. 2000 ms)
            WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(containerId).exec(waitCallback);

            boolean completedInTime = waitCallback.awaitCompletion(timeoutMillis, TimeUnit.MILLISECONDS);
            long executionTimeMs = System.currentTimeMillis() - startTime;

            if (!completedInTime) {
                log.warn("Container {} timed out (> {} ms). Forcibly killing.", containerId, timeoutMillis);
                forceKill(containerId);
                return ExecutionResult.builder()
                        .verdict(Verdict.TIME_LIMIT_EXCEEDED)
                        .executionTimeMs(executionTimeMs)
                        .exitCode(137)
                        .stdout("")
                        .stderr("Time Limit Exceeded.")
                        .build();
            }

            try {
                attachCallback.close();
            } catch (IOException ignored) {}

            int exitCode = waitCallback.awaitStatusCode();

            // Verify if process was terminated by Docker OOM Killer
            var inspectInfo = dockerClient.inspectContainerCmd(containerId).exec();
            if (Boolean.TRUE.equals(inspectInfo.getState().getOOMKilled())) {
                return ExecutionResult.builder()
                        .verdict(Verdict.MEMORY_LIMIT_EXCEEDED)
                        .executionTimeMs(executionTimeMs)
                        .exitCode(exitCode)
                        .stdout("")
                        .stderr("Memory Limit Exceeded.")
                        .build();
            }

            Verdict verdict = (exitCode == 0) ? Verdict.ACCEPTED : Verdict.RUNTIME_ERROR;

            return ExecutionResult.builder()
                    .verdict(verdict)
                    .stdout(stdoutStream.toString(StandardCharsets.UTF_8).trim())
                    .stderr(stderrStream.toString(StandardCharsets.UTF_8).trim())
                    .exitCode(exitCode)
                    .executionTimeMs(executionTimeMs)
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Execution interrupted for container {}", containerId, e);
            return ExecutionResult.builder()
                    .verdict(Verdict.INTERNAL_ERROR)
                    .stderr("Execution interrupted.")
                    .build();
        } catch (Exception e) {
            log.error("Docker execution failure: {}", e.getMessage(), e);
            return ExecutionResult.builder()
                    .verdict(Verdict.INTERNAL_ERROR)
                    .stderr(e.getMessage())
                    .build();
        } finally {
            // 5. Guaranteed Container Cleanup
            if (containerId != null) {
                destroyContainer(containerId);
            }
        }
    }

    private void forceKill(String containerId) {
        try {
            dockerClient.killContainerCmd(containerId).withSignal("SIGKILL").exec();
        } catch (Exception e) {
            log.warn("Failed to send SIGKILL to container {}: {}", containerId, e.getMessage());
        }
    }

    private void destroyContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        } catch (Exception e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }
}
