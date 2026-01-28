package com.ifoodbuy.spring_ai_demo.controller;

import com.ifoodbuy.spring_ai_demo.dto.ConversationDetail;
import com.ifoodbuy.spring_ai_demo.dto.ConversationListResponse;
import com.ifoodbuy.spring_ai_demo.dto.UpdateTitleRequest;
import com.ifoodbuy.spring_ai_demo.service.ConversationHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat/conversations")
public class ConversationHistoryController {

    private final ConversationHistoryService conversationHistoryService;

    public ConversationHistoryController(ConversationHistoryService conversationHistoryService) {
        this.conversationHistoryService = conversationHistoryService;
    }

    @GetMapping
    public ResponseEntity<ConversationListResponse> listConversations(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(conversationHistoryService.listConversations(keyword, page, pageSize));
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDetail> getConversation(@PathVariable String conversationId) {
        return ResponseEntity.ok(conversationHistoryService.getConversation(conversationId));
    }

    @PutMapping("/{conversationId}/title")
    public ResponseEntity<Void> updateTitle(
            @PathVariable String conversationId,
            @RequestBody UpdateTitleRequest request) {
        conversationHistoryService.updateTitle(conversationId, request.getTitle());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/title")
    public ResponseEntity<Void> saveTitle(
            @PathVariable String conversationId,
            @RequestBody UpdateTitleRequest request) {
        conversationHistoryService.saveOrUpdateTitle(conversationId, request.getTitle());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        conversationHistoryService.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }
}
