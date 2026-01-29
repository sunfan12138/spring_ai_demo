package com.ifoodbuy.spring_ai_demo.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Data
public class UploadDocumentRequest {
    private MultipartFile file;
    private Long spaceId;
    private String documentName;
}
