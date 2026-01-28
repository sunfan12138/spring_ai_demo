package com.ifoodbuy.spring_ai_demo.dto;

import lombok.Data;

import java.util.Map;

@Data
public class UploadDocumentRequest {
    private String text;
    private Map<String, Object> metadata;
}
