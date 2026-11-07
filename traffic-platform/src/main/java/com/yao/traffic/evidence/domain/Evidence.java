package com.yao.traffic.evidence.domain;

public record Evidence(long id, long ownerId, String objectKey, String sha256, String mediaType, long sizeBytes) {
    public Metadata metadata() { return new Metadata(id, sha256, mediaType, sizeBytes); }
    public record Metadata(long id, String sha256, String mediaType, long sizeBytes) { }
}
