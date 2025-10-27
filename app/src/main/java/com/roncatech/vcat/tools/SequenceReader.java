/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

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


