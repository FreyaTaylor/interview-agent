package com.interview.agent.interview.controller;

import com.interview.agent.interview.dto.CheckDuplicateResponse;
import com.interview.agent.interview.dto.DeleteResponse;
import com.interview.agent.interview.dto.FinalizeResponse;
import com.interview.agent.interview.dto.InterviewHistoryDetailResponse;
import com.interview.agent.interview.dto.InterviewHistoryItem;
import com.interview.agent.interview.dto.PreviewParseResponse;
import com.interview.agent.interview.dto.SaveDraftResponse;
import com.interview.agent.interview.dto.UpdateMetaResponse;
import com.interview.agent.interview.service.InterviewAsrService;
import com.interview.agent.interview.service.InterviewBasicService;
import com.interview.agent.interview.service.InterviewParseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InterviewController 集成测试骨架（WebMvc 层）。
 *
 * <p>目标：
 * 1. 确认 11 个端点路由可达且返回统一包裹结构
 * 2. 确认 snake_case 请求字段可正确绑定到 DTO
 *
 * <p>说明：
 * - 这里只校验 Controller 契约，不走数据库；业务逻辑由 service 层测试补齐。
 */
@WebMvcTest(InterviewController.class)
class InterviewControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InterviewParseService interviewParseService;

    @MockBean
    private InterviewBasicService interviewBasicService;

    @MockBean
    private InterviewAsrService interviewAsrService;

    @Test
    void previewParse_shouldReturnWrappedResponse() throws Exception {
        when(interviewParseService.previewParse(anyString()))
                .thenReturn(new PreviewParseResponse(List.of(), List.of(), "ok"));

        mockMvc.perform(post("/api/interview/preview-parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "text": "面试官：你好\\n我：你好",
                                  "company": "A",
                                  "position": "B"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary").value("ok"));
    }

    @Test
    void parse_shouldReturnWrappedResponse() throws Exception {
        when(interviewParseService.parseInterview(anyString(), anyString(), anyString()))
                .thenReturn(new FinalizeResponse(1L, List.of(), List.of(), Map.of(), 60, "一般", Map.of("comment", "ok")));

        mockMvc.perform(post("/api/interview/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "text": "x",
                                  "company": "A",
                                  "position": "B"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.record_id").value(1));
    }

    @Test
    void checkDuplicate_shouldReturnWrappedResponse() throws Exception {
        when(interviewBasicService.checkDuplicate(anyString()))
                .thenReturn(new CheckDuplicateResponse(true, 99L, "A", "B", "2026-06-08T00:00:00", 70));

        mockMvc.perform(post("/api/interview/check-duplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "text_hash": "hash"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.duplicate").value(true));
    }

    @Test
    void draft_shouldReturnWrappedResponse() throws Exception {
        when(interviewBasicService.saveDraft(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new SaveDraftResponse(10L, true, false));

        mockMvc.perform(post("/api/interview/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "record_id": 10,
                                  "turns": [{"id":1,"speaker":"面试官","content":"q"}],
                                  "groups": [{"type":"other","tag":"x","turn_ids":[1],"questions":["q"],"user_answer":"a","original_dialogue":"d"}],
                                  "company": "A",
                                  "position": "B"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.record_id").value(10));
    }

    @Test
    void historyEndpoints_shouldReturnWrappedResponse() throws Exception {
        when(interviewBasicService.historyList()).thenReturn(List.of(new InterviewHistoryItem(1L, "A", "B", 60, "一般", "2026", true, false)));
        when(interviewBasicService.historyDetail(anyLong())).thenReturn(new InterviewHistoryDetailResponse(
                1L, "A", "B", "raw", List.of(), List.of(), "", Map.of(), 60, "一般", Map.of(), false, "2026", false, true, null, null
        ));
        when(interviewBasicService.historyRecalibrate(anyLong(), any(), any()))
                .thenReturn(new FinalizeResponse(2L, List.of(), List.of(), Map.of(), 66, "一般", Map.of()));
        when(interviewBasicService.historyDelete(anyLong())).thenReturn(new DeleteResponse(true));
        when(interviewBasicService.updateMeta(anyLong(), anyString(), anyString()))
                .thenReturn(new UpdateMetaResponse(1L, "C", "D"));

        mockMvc.perform(get("/api/interview/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/interview/history/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/interview/history/1/recalibrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "turns": [{"id":1,"speaker":"面试官","content":"q"}],
                                  "groups": [{"type":"other","tag":"x","turn_ids":[1],"questions":["q"],"user_answer":"a","original_dialogue":"d"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(delete("/api/interview/history/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(patch("/api/interview/history/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"company\":\"C\",\"position\":\"D\"" + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void overwrite_shouldReturnWrappedResponse() throws Exception {
        when(interviewBasicService.overwrite(anyLong())).thenReturn(new DeleteResponse(true));

        mockMvc.perform(post("/api/interview/overwrite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"record_id\":5" + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deleted").value(true));
    }
}
