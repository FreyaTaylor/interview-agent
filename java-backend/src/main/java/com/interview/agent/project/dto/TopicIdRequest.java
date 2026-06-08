package com.interview.agent.project.dto;

import jakarta.validation.constraints.NotNull;

/** body {topic_id} —— L2 话题 id（即 project_node level=2 行）。 */
public record TopicIdRequest(
        @NotNull Long topicId
) {
}
