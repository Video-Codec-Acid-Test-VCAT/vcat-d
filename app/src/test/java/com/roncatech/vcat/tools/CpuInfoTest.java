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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class CpuInfoTest {
    private static final String JSON =
            "{\n" +
                    "  \"architecture\": \"ARMv8\",\n" +
                    "  \"cores\": {\n" +
                    "    \"core0\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core1\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core2\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core3\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core4\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core5\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core6\": \"Cortex-A75 (0xd0a): 1612 MHz\",\n" +
                    "    \"core7\": \"Cortex-A75 (0xd0a): 1612 MHz\"\n" +
                    "  }\n" +
                    "}";

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(CpuInfo.class, new CpuInfo.Adapter())
            .setPrettyPrinting()
            .create();

    public static CpuInfo buildTestInstance(){
        List<CpuInfo.CpuCore> cores = new ArrayList<>();
        for(int i = 0; i < 6; ++i){
            cores.add(new CpuInfo.CpuCore(0xd05, 1612));
        }
        cores.add(new CpuInfo.CpuCore(0xd0a, 1612));
        cores.add(new CpuInfo.CpuCore(0xd0a, 1612));

        return new CpuInfo("ARMv8", cores);

    }

    @Test
    public void testSerialize() {
        CpuInfo testInst = buildTestInstance();

        String jsonOutput = gson.toJson(testInst, CpuInfo.class);
        String exptOutput = JSON;
        assertEquals(exptOutput, jsonOutput);
    }

    @Test
    public void testDeserialize() {
        CpuInfo expected = buildTestInstance();

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(CpuInfo.class, new CpuInfo.Adapter())
                .setPrettyPrinting()
                .create();

        CpuInfo testInstance = gson.fromJson(JSON, CpuInfo.class);

        assertEquals(expected, testInstance);
    }
}
