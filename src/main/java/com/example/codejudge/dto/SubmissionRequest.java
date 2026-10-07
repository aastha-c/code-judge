package com.example.codejudge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "Problem ID is required")
    private Long problemId;

    @NotBlank(message = "Language is required (python, java, cpp)")
    private String language;

    @NotBlank(message = "Source code cannot be blank")
    private String sourceCode;
}
