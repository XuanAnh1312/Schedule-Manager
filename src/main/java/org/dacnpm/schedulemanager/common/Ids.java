package org.dacnpm.schedulemanager.common;

import java.util.UUID;

/** Sinh khóa chính dạng {@code prefix_uuid} (≤ 64 ký tự, khớp VARCHAR(64)). */
public final class Ids {

    private Ids() {}

    public static String next(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
