package com.yao.traffic.evidence.domain;

/** Binary view with a server-generated disposition name, never a filesystem object key. */
public record EvidenceContent(byte[] bytes,String mediaType,String filename) { }
