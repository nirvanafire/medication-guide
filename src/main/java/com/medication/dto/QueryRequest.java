package com.medication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class QueryRequest {

    @NotBlank(message = "问题不能为空")
    private String question;

    private String drugName;

    @Min(value = 1, message = "top_k最小为1")
    @Max(value = 10, message = "top_k最大为10")
    private Integer topK = 3;

    private Boolean stream = false;

    private String sessionId;

    private String userId;
}