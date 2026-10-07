package com.example.codejudge.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long problemId;

    @Column(nullable = false)
    private String language; // python, java, cpp

    @Column(columnDefinition = "TEXT", nullable = false)
    private String sourceCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status;

    @Enumerated(EnumType.STRING)
    private Verdict verdict;

    private Long executionTimeMs;

    private Long memoryUsedKb;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String outputLogs;

    @Builder.Default
    private Double problemScore = 100.0;

    @Column(nullable = false)
    private Instant createdAt;
}
