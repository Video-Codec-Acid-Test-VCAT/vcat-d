package com.roncatech.extension_dav1d;

import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;

final class Dav1dOutputBuffer extends VideoDecoderOutputBuffer {
    /** Native handle to a held Dav1dPicture (0 when none). */
    long nativePic;

    Dav1dOutputBuffer(Owner owner) {
        super(owner);
    }

    @Override
    public void clear() {
        super.clear();
        nativePic = 0;
    }
}
