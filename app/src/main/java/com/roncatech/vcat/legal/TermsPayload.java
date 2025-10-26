package com.roncatech.vcat.legal;

public class TermsPayload {
    public final int version;
    public final String html;
    public final String etag;

    public TermsPayload(int version, String html, String etag) {
        this.version = version;
        this.html = html;
        this.etag = etag;
    }
}

