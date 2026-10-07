package com.example.codejudge.worker;

import com.example.codejudge.entity.Submission;
import com.example.codejudge.entity.SubmissionStatus;
import com.example.codejudge.entity.TestCase;
import com.example.codejudge.entity.Verdict;
import com.example.codejudge.repository.SubmissionRepository;
import com.example.codejudge.repository.TestCaseRepository;
import com.example.codejudge.service.DockerSandboxService;
import com.example.codejudge.service.DockerSandboxService.ExecutionResult;
import com.example.codejudge.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionWorker {

    private final SubmissionRepository submissionRepository;
    private final TestCaseRepository testCaseRepository;
    private final DockerSandboxService sandboxService;
    private final LeaderboardService leaderboardService;

    @RabbitListener(
            queues = "${judge.rabbitmq.queue:submissionQueue}",
            concurrency = "5-20"
    )
    public void consumeSubmission(Long submissionId) {
        log.info("Worker picked up submission ID: {}", submissionId);

        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            log.error("Submission ID {} not found in database.", submissionId);
            return;
        }

        submission.setStatus(SubmissionStatus.IN_PROGRESS);
        submissionRepository.save(submission);

        Path tempDir = null;
        try {
            // 1. Prepare host execution directory
            tempDir = Files.createTempDirectory("judge_sub_" + submission.getId() + "_");
            writeSourceFile(tempDir, submission.getLanguage(), submission.getSourceCode());

            String dockerImage = resolveDockerImage(submission.getLanguage());

            // 2. Compilation step (for compiled languages)
            String[] compileCmd = resolveCompileCommand(submission.getLanguage());
            if (compileCmd != null) {
                ExecutionResult compileRes = sandboxService.execute(
                        dockerImage,
                        compileCmd,
                        tempDir.toAbsolutePath().toString(),
                        "",
                        10_000L // 10s compile limit
                );

                if (compileRes.getExitCode() != 0) {
                    submission.setStatus(SubmissionStatus.COMPLETED);
                    submission.setVerdict(Verdict.COMPILATION_ERROR);
                    submission.setErrorMessage(compileRes.getStderr().isBlank() ? compileRes.getStdout() : compileRes.getStderr());
                    submissionRepository.save(submission);
                    return;
                }
            }

            // 3. Test Case Evaluation
            List<TestCase> testCases = testCaseRepository.findByProblemId(submission.getProblemId());
            Verdict finalVerdict = Verdict.ACCEPTED;
            long maxTime = 0L;
            String errorMessage = null;
            String outputLogs = "";
            String[] runCmd = resolveRunCommand(submission.getLanguage());

            for (TestCase tc : testCases) {
                ExecutionResult result = sandboxService.execute(
                        dockerImage,
                        runCmd,
                        tempDir.toAbsolutePath().toString(),
                        tc.getInput(),
                        tc.getTimeLimitMillis()
                );

                maxTime = Math.max(maxTime, result.getExecutionTimeMs());
                outputLogs = result.getStdout();

                if (result.getVerdict() != Verdict.ACCEPTED) {
                    finalVerdict = result.getVerdict();
                    errorMessage = result.getStderr();
                    break;
                }

                // Verify output
                if (!result.getStdout().trim().equals(tc.getExpectedOutput().trim())) {
                    finalVerdict = Verdict.WRONG_ANSWER;
                    errorMessage = "Expected: " + tc.getExpectedOutput().trim() + ", Got: " + result.getStdout().trim();
                    break;
                }
            }

            // 4. Update Submission Verdict
            submission.setStatus(SubmissionStatus.COMPLETED);
            submission.setVerdict(finalVerdict);
            submission.setExecutionTimeMs(maxTime);
            submission.setOutputLogs(outputLogs);
            submission.setErrorMessage(errorMessage);
            submissionRepository.save(submission);

            // 5. Update Leaderboard on AC
            if (finalVerdict == Verdict.ACCEPTED) {
                leaderboardService.incrementScore(
                        submission.getUserId().toString(),
                        submission.getProblemScore()
                );
            }

        } catch (Exception e) {
            log.error("Fatal error evaluating submission {}: {}", submissionId, e.getMessage(), e);
            submission.setStatus(SubmissionStatus.FAILED);
            submission.setVerdict(Verdict.INTERNAL_ERROR);
            submission.setErrorMessage(e.getMessage());
            submissionRepository.save(submission);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private void writeSourceFile(Path dir, String language, String source) throws IOException {
        String filename = switch (language.toLowerCase()) {
            case "python" -> "solution.py";
            case "java" -> "Solution.java";
            case "cpp" -> "solution.cpp";
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
        Files.writeString(dir.resolve(filename), source);
    }

    private String[] resolveCompileCommand(String language) {
        return switch (language.toLowerCase()) {
            case "java" -> new String[]{"javac", "Solution.java"};
            case "cpp" -> new String[]{"g++", "-O3", "solution.cpp", "-o", "solution.out"};
            case "python" -> null;
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }

    private String[] resolveRunCommand(String language) {
        return switch (language.toLowerCase()) {
            case "python" -> new String[]{"python3", "solution.py"};
            case "java" -> new String[]{"java", "-cp", "/app", "Solution"};
            case "cpp" -> new String[]{"./solution.out"};
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }

    private String resolveDockerImage(String language) {
        return switch (language.toLowerCase()) {
            case "python" -> "python:3.11-slim";
            case "java" -> "openjdk:21-slim";
            case "cpp" -> "gcc:latest";
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }

    private void cleanupTempDir(Path dir) {
        if (dir != null && Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {}
                        });
            } catch (IOException e) {
                log.warn("Failed to delete temp dir {}: {}", dir, e.getMessage());
            }
        }
    }
}
