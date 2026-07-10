package me.psikuvit.betterads.storage;

import java.util.Set;

public final class UploadPolicy {

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime", "video/x-msvideo"
    );

    private UploadPolicy() {}
}
