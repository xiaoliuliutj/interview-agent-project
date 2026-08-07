package com.interview.agent.upper.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单 JVM 部署下的单调业务编号生成器，避免同一毫秒的并发上传发生主键冲突。
 * 多实例部署应替换为数据库序列或雪花 ID 服务。
 */
@Component
public class BusinessIdGenerator {
    private final AtomicLong lastIssued = new AtomicLong();

    public String next() {
        long value = lastIssued.updateAndGet(previous -> Math.max(System.currentTimeMillis(), previous + 1));
        return Long.toString(value);
    }
}
