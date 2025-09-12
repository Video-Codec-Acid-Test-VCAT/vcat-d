package com.roncatech.extension_dav1d;

import com.google.android.exoplayer2.decoder.DecoderException;

public final class Dav1dDecoderException extends DecoderException {
    public Dav1dDecoderException(String msg) { super(msg); }
    public Dav1dDecoderException(Throwable cause) { super(cause); }
    public Dav1dDecoderException(String msg, Throwable cause) { super(msg, cause); }
}
