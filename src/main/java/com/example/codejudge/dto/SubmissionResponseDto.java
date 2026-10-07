package com.example.codejudge.dto;

import com.example.codejudge.entity.SubmissionStatus;
import com.example.codejudge.entity.Verdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionResponseDto {
    private Long submissionId;
    private Long problemId;
    private String language;
    private SubmissionStatus status;
    private Verdict verdict;
    private Long executionTimeMs;
    private Long memoryUsedKb;
    private String outputLogs;
    private String errorMessage;
    private LocalDateTime createdAt;
}
