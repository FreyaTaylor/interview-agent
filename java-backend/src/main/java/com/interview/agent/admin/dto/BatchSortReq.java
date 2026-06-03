package com.interview.agent.admin.dto;

import java.util.List;

/**
 * 批量调整 sort_order 请求。
 */
public record BatchSortReq(List<Item> updates) {
    public record Item(long id, int sortOrder) {
    }
}
