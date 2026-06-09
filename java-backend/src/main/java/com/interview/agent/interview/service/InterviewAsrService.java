package com.interview.agent.interview.service;

import org.springframework.web.multipart.MultipartFile;

/** 面试音频转写服务。 */
public interface InterviewAsrService {

    /** 上传音频并返回转写文本。 */
    String transcribe(MultipartFile file);
}
