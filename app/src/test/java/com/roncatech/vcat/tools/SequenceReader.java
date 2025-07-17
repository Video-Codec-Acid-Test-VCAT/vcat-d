package com.roncatech.vcat.tools;

import java.io.IOException;
import java.io.Reader;

/**
 * A Reader that first drains `first` and then continues with `second`.
 */
public class SequenceReader extends Reader {
    private final Reader first, second;
    private boolean usingFirst = true;

    public SequenceReader(Reader first, Reader second) {
        this.first  = first;
        this.second = second;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        if (usingFirst) {
            int cnt = first.read(cbuf, off, len);
            if (cnt != -1) return cnt;
            usingFirst = false;
        }
        return second.read(cbuf, off, len);
    }

    @Override
    public void close() throws IOException {
        try { first.close();  } finally {
            second.close();
        }
    }
}


