package com.example.codejudge.controller;

import com.example.codejudge.dto.SubmissionRequest;
import com.example.codejudge.dto.SubmissionResponseDto;
import com.example.codejudge.entity.Submission;
import com.example.codejudge.entity.SubmissionStatus;
import com.example.codejudge.repository.SubmissionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@RestController
@RequestMapping("/api/v1/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionRepository submissionRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${judge.rabbitmq.exchange:submissionExchange}")
    private String exchange;

    @Value("${judge.rabbitmq.routing-key:submissionRoutingKey}")
    private String routingKey;

    @PostMapping
    public ResponseEntity<SubmissionResponseDto> submitCode(@Valid @RequestBody SubmissionRequest request) {
        Submission submission = Submission.builder()
                .userId(request.getUserId())
                .problemId(request.getProblemId())
                .language(request.getLanguage().toLowerCase())
                .sourceCode(request.getSourceCode())
                .status(SubmissionStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        submission = submissionRepository.save(submission);

        // Enqueue submissionId for RabbitMQ worker
        rabbitTemplate.convertAndSend(exchange, routingKey, submission.getId());
        log.info("Queued submission ID: {} into RabbitMQ", submission.getId());

        LocalDateTime createdAt = LocalDateTime.ofInstant(submission.getCreatedAt(), ZoneId.systemDefault());

        SubmissionResponseDto response = SubmissionResponseDto.builder()
                .submissionId(submission.getId())
                .problemId(submission.getProblemId())
                .language(submission.getLanguage())
                .status(submission.getStatus())
                .createdAt(createdAt)
                .build();

        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/submissions/" + submission.getId()))
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubmissionResponseDto> getSubmission(@PathVariable Long id) {
        Submission submission = submissionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found with ID: " + id));

        LocalDateTime createdAt = LocalDateTime.ofInstant(submission.getCreatedAt(), ZoneId.systemDefault());

        SubmissionResponseDto response = SubmissionResponseDto.builder()
                .submissionId(submission.getId())
                .problemId(submission.getProblemId())
                .language(submission.getLanguage())
                .status(submission.getStatus())
                .verdict(submission.getVerdict())
                .executionTimeMs(submission.getExecutionTimeMs())
                .memoryUsedKb(submission.getMemoryUsedKb())
                .outputLogs(submission.getOutputLogs())
                .errorMessage(submission.getErrorMessage())
                .createdAt(createdAt)
                .build();

        return ResponseEntity.ok(response);
    }
}
