package com.interviewguide.utils.id;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 鍗?JVM 閮ㄧ讲涓嬬殑鍗曡皟涓氬姟缂栧彿鐢熸垚鍣紝閬垮厤鍚屼竴姣鐨勫苟鍙戜笂浼犲彂鐢熶富閿啿绐併€? * 澶氬疄渚嬮儴缃插簲鏇挎崲涓烘暟鎹簱搴忓垪鎴栭洩鑺?ID 鏈嶅姟銆? */
@Component
public class BusinessIdGenerator {
    private final AtomicLong lastIssued = new AtomicLong();

    public String next() {
        long value = lastIssued.updateAndGet(previous -> Math.max(System.currentTimeMillis(), previous + 1));
        return Long.toString(value);
    }
}
