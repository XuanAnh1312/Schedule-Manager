package org.dacnpm.schedulemanager.api.requests;

/** Trạng thái yêu cầu đổi ca (khớp CHECK constraint ở V6). */
public final class ShiftRequestStatus {

    private ShiftRequestStatus() {}

    public static final String PENDING_PEER = "pending_peer";
    public static final String PENDING_APPROVAL = "pending_approval";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String PEER_DECLINED = "peer_declined";
    public static final String CANCELLED = "cancelled";

    public static final String TYPE_SWAP = "swap";
    public static final String TYPE_COVER = "cover";
}
