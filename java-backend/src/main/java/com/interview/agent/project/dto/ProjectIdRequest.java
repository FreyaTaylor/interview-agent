package com.interview.agent.project.dto;

import jakarta.validation.constraints.NotNull;

/** body {project_id} — 多个 project-grilling 端点共用。 */
public record ProjectIdRequest(
        @NotNull Long projectId
) {
}
